/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import akka.contrib.pattern.Shard.{ ShardCommand, StateChange }
import akka.contrib.pattern.ShardCoordinator.Internal.SnapshotTick

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.actor.NoSerializationVerificationNeeded
import akka.actor.PoisonPill
import akka.actor.Props
import akka.actor.ReceiveTimeout
import akka.actor.RootActorPath
import akka.actor.Terminated
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.Member
import akka.cluster.MemberStatus
import akka.pattern.ask
import akka.persistence._
import akka.cluster.ClusterEvent.ClusterDomainEvent

/**
 * This extension provides sharding functionality of actors in a cluster.
 * The typical use case is when you have many stateful actors that together consume
 * more resources (e.g. memory) than fit on one machine. You need to distribute them across
 * several nodes in the cluster and you want to be able to interact with them using their
 * logical identifier, but without having to care about their physical location in the cluster,
 * which might also change over time. It could for example be actors representing Aggregate Roots in
 * Domain-Driven Design terminology. Here we call these actors "entries". These actors
 * typically have persistent (durable) state, but this feature is not limited to
 * actors with persistent state.
 *
 * In this context sharding means that actors with an identifier, so called entries,
 * can be automatically distributed across multiple nodes in the cluster. Each entry
 * actor runs only at one place, and messages can be sent to the entry without requiring
 * the sender to know the location of the destination actor. This is achieved by sending
 * the messages via a [[ShardRegion]] actor provided by this extension, which knows how
 * to route the message with the entry id to the final destination.
 *
 * This extension is supposed to be used by first, typically at system startup on each node
 * in the cluster, registering the supported entry types with the [[ClusterSharding#start]]
 * method and then the `ShardRegion` actor for a named entry type can be retrieved with
 * [[ClusterSharding#shardRegion]]. Messages to the entries are always sent via the local
 * `ShardRegion`. Some settings can be configured as described in the `akka.contrib.cluster.sharding`
 * section of the `reference.conf`.
 *
 * The `ShardRegion` actor is started on each node in the cluster, or group of nodes
 * tagged with a specific role. The `ShardRegion` is created with two application specific
 * functions to extract the entry identifier and the shard identifier from incoming messages.
 * A shard is a group of entries that will be managed together. For the first message in a
 * specific shard the `ShardRegion` request the location of the shard from a central coordinator,
 * the [[ShardCoordinator]]. The `ShardCoordinator` decides which `ShardRegion` that
 * owns the shard. The `ShardRegion` receives the decided home of the shard
 * and if that is the `ShardRegion` instance itself it will create a local child
 * actor representing the entry and direct all messages for that entry to it.
 * If the shard home is another `ShardRegion` instance messages will be forwarded
 * to that `ShardRegion` instance instead. While resolving the location of a
 * shard incoming messages for that shard are buffered and later delivered when the
 * shard home is known. Subsequent messages to the resolved shard can be delivered
 * to the target destination immediately without involving the `ShardCoordinator`.
 *
 * To make sure that at most one instance of a specific entry actor is running somewhere
 * in the cluster it is important that all nodes have the same view of where the shards
 * are located. Therefore the shard allocation decisions are taken by the central
 * `ShardCoordinator`, which is running as a cluster singleton, i.e. one instance on
 * the oldest member among all cluster nodes or a group of nodes tagged with a specific
 * role. The oldest member can be determined by [[akka.cluster.Member#isOlderThan]].
 *
 * The logic that decides where a shard is to be located is defined in a pluggable shard
 * allocation strategy. The default implementation [[ShardCoordinator.LeastShardAllocationStrategy]]
 * allocates new shards to the `ShardRegion` with least number of previously allocated shards.
 * This strategy can be replaced by an application specific implementation.
 *
 * To be able to use newly added members in the cluster the coordinator facilitates rebalancing
 * of shards, i.e. migrate entries from one node to another. In the rebalance process the
 * coordinator first notifies all `ShardRegion` actors that a handoff for a shard has started.
 * That means they will start buffering incoming messages for that shard, in the same way as if the
 * shard location is unknown. During the rebalance process the coordinator will not answer any
 * requests for the location of shards that are being rebalanced, i.e. local buffering will
 * continue until the handoff is completed. The `ShardRegion` responsible for the rebalanced shard
 * will stop all entries in that shard by sending `PoisonPill` to them. When all entries have
 * been terminated the `ShardRegion` owning the entries will acknowledge the handoff as completed
 * to the coordinator. Thereafter the coordinator will reply to requests for the location of
 * the shard and thereby allocate a new home for the shard and then buffered messages in the
 * `ShardRegion` actors are delivered to the new location. This means that the state of the entries
 * are not transferred or migrated. If the state of the entries are of importance it should be
 * persistent (durable), e.g. with `akka-persistence`, so that it can be recovered at the new
 * location.
 *
 * The logic that decides which shards to rebalance is defined in a pluggable shard
 * allocation strategy. The default implementation [[ShardCoordinator.LeastShardAllocationStrategy]]
 * picks shards for handoff from the `ShardRegion` with most number of previously allocated shards.
 * They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
 * i.e. new members in the cluster. There is a configurable threshold of how large the difference
 * must be to begin the rebalancing. This strategy can be replaced by an application specific
 * implementation.
 *
 * The state of shard locations in the `ShardCoordinator` is persistent (durable) with
 * `akka-persistence` to survive failures. Since it is running in a cluster `akka-persistence`
 * must be configured with a distributed journal. When a crashed or unreachable coordinator
 * node has been removed (via down) from the cluster a new `ShardCoordinator` singleton
 * actor will take over and the state is recovered. During such a failure period shards
 * with known location are still available, while messages for new (unknown) shards
 * are buffered until the new `ShardCoordinator` becomes available.
 *
 * As long as a sender uses the same `ShardRegion` actor to deliver messages to an entry
 * actor the order of the messages is preserved. As long as the buffer limit is not reached
 * messages are delivered on a best effort basis, with at-most once delivery semantics,
 * in the same way as ordinary message sending. Reliable end-to-end messaging, with
 * at-least-once semantics can be added by using `AtLeastOnceDelivery` in `akka-persistence`.
 *
 * Some additional latency is introduced for messages targeted to new or previously
 * unused shards due to the round-trip to the coordinator. Rebalancing of shards may
 * also add latency. This should be considered when designing the application specific
 * shard resolution, e.g. to avoid too fine grained shards.
 *
 * The `ShardRegion` actor can also be started in proxy only mode, i.e. it will not
 * host any entries itself, but knows how to delegate messages to the right location.
 * A `ShardRegion` starts in proxy only mode if the roles of the node does not include
 * the node role specified in `akka.contrib.cluster.sharding.role` config property
 * or if the specified `entryProps` is `None`/`null`.
 *
 * If the state of the entries are persistent you may stop entries that are not used to
 * reduce memory consumption. This is done by the application specific implementation of
 * the entry actors for example by defining receive timeout (`context.setReceiveTimeout`).
 * If a message is already enqueued to the entry when it stops itself the enqueued message
 * in the mailbox will be dropped. To support graceful passivation without loosing such
 * messages the entry actor can send [[ShardRegion.Passivate]] to its parent `ShardRegion`.
 * The specified wrapped message in `Passivate` will be sent back to the entry, which is
 * then supposed to stop itself. Incoming messages will be buffered by the `ShardRegion`
 * between reception of `Passivate` and termination of the entry. Such buffered messages
 * are thereafter delivered to a new incarnation of the entry.
 *
 */
object ClusterSharding extends ExtensionId[ClusterSharding] with ExtensionIdProvider {
  override def get(system: ActorSystem): ClusterSharding = super.get(system)

  override def lookup = ClusterSharding

  override def createExtension(system: ExtendedActorSystem): ClusterSharding =
    new ClusterSharding(system)
}

/**
 * @see [[ClusterSharding$ ClusterSharding companion object]]
 */
class ClusterSharding(system: ExtendedActorSystem) extends Extension {
  import ClusterShardingGuardian._
  import ShardCoordinator.ShardAllocationStrategy
  import ShardCoordinator.LeastShardAllocationStrategy

  private val cluster = Cluster(system)
  /**
   * INTERNAL API
   */
  private[akka] object Settings {
    val config = system.settings.config.getConfig("akka.contrib.cluster.sharding")

    val Role: Option[String] = config.getString("role") match {
      case "" ⇒ None
      case r  ⇒ Some(r)
    }
    def hasNecessaryClusterRole(role: Option[String]): Boolean = role.forall(cluster.selfRoles.contains)

    val GuardianName: String = config.getString("guardian-name")
    val CoordinatorFailureBackoff = config.getDuration("coordinator-failure-backoff", MILLISECONDS).millis
    val RetryInterval: FiniteDuration = config.getDuration("retry-interval", MILLISECONDS).millis
    val BufferSize: Int = config.getInt("buffer-size")
    val HandOffTimeout: FiniteDuration = config.getDuration("handoff-timeout", MILLISECONDS).millis
    val ShardStartTimeout: FiniteDuration = config.getDuration("shard-start-timeout", MILLISECONDS).millis
    val ShardFailureBackoff = config.getDuration("shard-failure-backoff", MILLISECONDS).millis
    val EntryRestartBackoff = config.getDuration("entry-restart-backoff", MILLISECONDS).millis
    val RebalanceInterval: FiniteDuration = config.getDuration("rebalance-interval", MILLISECONDS).millis
    val SnapshotInterval: FiniteDuration = config.getDuration("snapshot-interval", MILLISECONDS).millis
    val LeastShardAllocationRebalanceThreshold: Int =
      config.getInt("least-shard-allocation-strategy.rebalance-threshold")
    val LeastShardAllocationMaxSimultaneousRebalance: Int =
      config.getInt("least-shard-allocation-strategy.max-simultaneous-rebalance")
  }
  import Settings._
  private val regions: ConcurrentHashMap[String, ActorRef] = new ConcurrentHashMap
  private lazy val guardian = system.actorOf(Props[ClusterShardingGuardian], Settings.GuardianName)

  /**
   * Scala API: Register a named entry type by defining the [[akka.actor.Props]] of the entry actor
   * and functions to extract entry and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.contrib.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entry type
   * @param entryProps the `Props` of the entry actors that will be created by the `ShardRegion`,
   *   if not defined (None) the `ShardRegion` on this node will run in proxy only mode, i.e.
   *   it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   *   entry actors itself
   * @param roleOverride specifies that this entry type requires cluster nodes with a specific role.
   *   if not defined (None), then defaults to standard behavior of using Role (if any) from configuration
   * @param rememberEntries true if entry actors shall created be automatically restarted upon `Shard`
   *   restart. i.e. if the `Shard` is started on a different `ShardRegion` due to rebalance or crash.
   * @param idExtractor partial function to extract the entry id and the message to send to the
   *   entry from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param shardResolver function to determine the shard id for an incoming message, only messages
   *   that passed the `idExtractor` will be used
   * @param allocationStrategy possibility to use a custom shard allocation and
   *   rebalancing logic
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entryProps: Option[Props],
    roleOverride: Option[String],
    rememberEntries: Boolean,
    idExtractor: ShardRegion.IdExtractor,
    shardResolver: ShardRegion.ShardResolver,
    allocationStrategy: ShardAllocationStrategy): ActorRef = {

    val resolvedRole = if (roleOverride == None) Role else roleOverride

    implicit val timeout = system.settings.CreationTimeout
    val startMsg = Start(typeName, entryProps, roleOverride, rememberEntries, idExtractor, shardResolver, allocationStrategy)
    val Started(shardRegion) = Await.result(guardian ? startMsg, timeout.duration)
    regions.put(typeName, shardRegion)
    shardRegion
  }

  /**
   * Register a named entry type by defining the [[akka.actor.Props]] of the entry actor and
   * functions to extract entry and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * The default shard allocation strategy [[ShardCoordinator.LeastShardAllocationStrategy]]
   * is used.
   *
   * Some settings can be configured as described in the `akka.contrib.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entry type
   * @param entryProps the `Props` of the entry actors that will be created by the `ShardRegion`,
   *   if not defined (None) the `ShardRegion` on this node will run in proxy only mode, i.e.
   *   it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   *   entry actors itself
   * @param roleOverride specifies that this entry type requires cluster nodes with a specific role.
   *   if not defined (None), then defaults to standard behavior of using Role (if any) from configuration
   * @param rememberEntries true if entry actors shall created be automatically restarted upon `Shard`
   *   restart. i.e. if the `Shard` is started on a different `ShardRegion` due to rebalance or crash.
   * @param idExtractor partial function to extract the entry id and the message to send to the
   *   entry from the incoming message, if the partial function does not match the message will
   *   be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
   * @param shardResolver function to determine the shard id for an incoming message, only messages
   *   that passed the `idExtractor` will be used
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entryProps: Option[Props],
    roleOverride: Option[String],
    rememberEntries: Boolean,
    idExtractor: ShardRegion.IdExtractor,
    shardResolver: ShardRegion.ShardResolver): ActorRef = {

    start(typeName, entryProps, roleOverride, rememberEntries, idExtractor, shardResolver,
      new LeastShardAllocationStrategy(LeastShardAllocationRebalanceThreshold, LeastShardAllocationMaxSimultaneousRebalance))
  }

  /**
   * Java API: Register a named entry type by defining the [[akka.actor.Props]] of the entry actor
   * and functions to extract entry and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * Some settings can be configured as described in the `akka.contrib.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entry type
   * @param entryProps the `Props` of the entry actors that will be created by the `ShardRegion`,
   *   if not defined (null) the `ShardRegion` on this node will run in proxy only mode, i.e.
   *   it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   *   entry actors itself
   * @param roleOverride specifies that this entry type requires cluster nodes with a specific role.
   *   if not defined (None), then defaults to standard behavior of using Role (if any) from configuration
   * @param rememberEntries true if entry actors shall created be automatically restarted upon `Shard`
   *   restart. i.e. if the `Shard` is started on a different `ShardRegion` due to rebalance or crash.
   * @param messageExtractor functions to extract the entry id, shard id, and the message to send to the
   *   entry from the incoming message
   * @param allocationStrategy possibility to use a custom shard allocation and
   *   rebalancing logic
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entryProps: Props,
    roleOverride: Option[String],
    rememberEntries: Boolean,
    messageExtractor: ShardRegion.MessageExtractor,
    allocationStrategy: ShardAllocationStrategy): ActorRef = {

    start(typeName, entryProps = Option(entryProps), roleOverride, rememberEntries,
      idExtractor = {
        case msg if messageExtractor.entryId(msg) ne null ⇒
          (messageExtractor.entryId(msg), messageExtractor.entryMessage(msg))
      },
      shardResolver = msg ⇒ messageExtractor.shardId(msg),
      allocationStrategy = allocationStrategy)
  }

  /**
   * Java API: Register a named entry type by defining the [[akka.actor.Props]] of the entry actor
   * and functions to extract entry and shard identifier from messages. The [[ShardRegion]] actor
   * for this type can later be retrieved with the [[#shardRegion]] method.
   *
   * The default shard allocation strategy [[ShardCoordinator.LeastShardAllocationStrategy]]
   * is used.
   *
   * Some settings can be configured as described in the `akka.contrib.cluster.sharding` section
   * of the `reference.conf`.
   *
   * @param typeName the name of the entry type
   * @param entryProps the `Props` of the entry actors that will be created by the `ShardRegion`,
   *   if not defined (null) the `ShardRegion` on this node will run in proxy only mode, i.e.
   *   it will delegate messages to other `ShardRegion` actors on other nodes, but not host any
   *   entry actors itself
   * @param roleOverride specifies that this entry type requires cluster nodes with a specific role.
   *   if not defined (None), then defaults to standard behavior of using Role (if any) from configuration
   * @param rememberEntries true if entry actors shall created be automatically restarted upon `Shard`
   *   restart. i.e. if the `Shard` is started on a different `ShardRegion` due to rebalance or crash.
   * @param messageExtractor functions to extract the entry id, shard id, and the message to send to the
   *   entry from the incoming message
   * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard
   */
  def start(
    typeName: String,
    entryProps: Props,
    roleOverride: Option[String],
    rememberEntries: Boolean,
    messageExtractor: ShardRegion.MessageExtractor): ActorRef = {

    start(typeName, entryProps, roleOverride, rememberEntries, messageExtractor,
      new LeastShardAllocationStrategy(LeastShardAllocationRebalanceThreshold, LeastShardAllocationMaxSimultaneousRebalance))
  }

  /**
   * Retrieve the actor reference of the [[ShardRegion]] actor responsible for the named entry type.
   * The entry type must be registered with the [[#start]] method before it can be used here.
   * Messages to the entry is always sent via the `ShardRegion`.
   */
  def shardRegion(typeName: String): ActorRef = regions.get(typeName) match {
    case null ⇒ throw new IllegalArgumentException(s"Shard type [$typeName] must be started first")
    case ref  ⇒ ref
  }

}

/**
 * INTERNAL API.
 */
private[akka] object ClusterShardingGuardian {
  import ShardCoordinator.ShardAllocationStrategy
  final case class Start(typeName: String, entryProps: Option[Props], role: Option[String], rememberEntries: Boolean,
                         idExtractor: ShardRegion.IdExtractor, shardResolver: ShardRegion.ShardResolver, allocationStrategy: ShardAllocationStrategy)
    extends NoSerializationVerificationNeeded
  final case class Started(shardRegion: ActorRef) extends NoSerializationVerificationNeeded
}

/**
 * INTERNAL API. [[ShardRegion]] and [[ShardCoordinator]] actors are createad as children
 * of this actor.
 */
private[akka] class ClusterShardingGuardian extends Actor {
  import ClusterShardingGuardian._

  val cluster = Cluster(context.system)
  val sharding = ClusterSharding(context.system)
  import sharding.Settings._

  def receive = {
    case Start(typeName, entryProps, role, rememberEntries, idExtractor, shardResolver, allocationStrategy) ⇒
      val encName = URLEncoder.encode(typeName, "utf-8")
      val coordinatorSingletonManagerName = encName + "Coordinator"
      val coordinatorPath = (self.path / coordinatorSingletonManagerName / "singleton" / "coordinator").toStringWithoutAddress
      val shardRegion = context.child(encName).getOrElse {
        val hasRequiredRole = hasNecessaryClusterRole(role)
        if (hasRequiredRole && context.child(coordinatorSingletonManagerName).isEmpty) {
          val coordinatorProps = ShardCoordinator.props(handOffTimeout = HandOffTimeout, shardStartTimeout = ShardStartTimeout,
            rebalanceInterval = RebalanceInterval, snapshotInterval = SnapshotInterval, allocationStrategy)
          val singletonProps = ShardCoordinatorSupervisor.props(CoordinatorFailureBackoff, coordinatorProps)
          context.actorOf(ClusterSingletonManager.props(
            singletonProps,
            singletonName = "singleton",
            terminationMessage = PoisonPill,
            role = role),
            name = coordinatorSingletonManagerName)
        }

        context.actorOf(ShardRegion.props(
          typeName = typeName,
          entryProps = if (hasRequiredRole) entryProps else None,
          role = role,
          coordinatorPath = coordinatorPath,
          retryInterval = RetryInterval,
          snapshotInterval = SnapshotInterval,
          shardFailureBackoff = ShardFailureBackoff,
          entryRestartBackoff = EntryRestartBackoff,
          bufferSize = BufferSize,
          rememberEntries = rememberEntries,
          idExtractor = idExtractor,
          shardResolver = shardResolver),
          name = encName)
      }
      sender() ! Started(shardRegion)

  }

}

/**
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
object ShardRegion {

  /**
   * Scala API: Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor.
   */
  def props(
    typeName: String,
    entryProps: Option[Props],
    role: Option[String],
    coordinatorPath: String,
    retryInterval: FiniteDuration,
    shardFailureBackoff: FiniteDuration,
    entryRestartBackoff: FiniteDuration,
    snapshotInterval: FiniteDuration,
    bufferSize: Int,
    rememberEntries: Boolean,
    idExtractor: ShardRegion.IdExtractor,
    shardResolver: ShardRegion.ShardResolver): Props =
    Props(new ShardRegion(typeName, entryProps, role, coordinatorPath, retryInterval, shardFailureBackoff, entryRestartBackoff, snapshotInterval, bufferSize, rememberEntries, idExtractor, shardResolver))

  /**
   * Scala API: Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor.
   */
  def props(
    typeName: String,
    entryProps: Props,
    role: Option[String],
    coordinatorPath: String,
    retryInterval: FiniteDuration,
    shardFailureBackoff: FiniteDuration,
    entryRestartBackoff: FiniteDuration,
    snapshotInterval: FiniteDuration,
    bufferSize: Int,
    rememberEntries: Boolean,
    idExtractor: ShardRegion.IdExtractor,
    shardResolver: ShardRegion.ShardResolver): Props =
    props(typeName, Some(entryProps), role, coordinatorPath, retryInterval, shardFailureBackoff, entryRestartBackoff, snapshotInterval, bufferSize, rememberEntries, idExtractor, shardResolver)

  /**
   * Java API: Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor.
   */
  def props(
    typeName: String,
    entryProps: Props,
    role: String,
    coordinatorPath: String,
    retryInterval: FiniteDuration,
    shardFailureBackoff: FiniteDuration,
    entryRestartBackoff: FiniteDuration,
    snapshotInterval: FiniteDuration,
    bufferSize: Int,
    rememberEntries: Boolean,
    messageExtractor: ShardRegion.MessageExtractor): Props = {

    props(typeName, Option(entryProps), roleOption(role), coordinatorPath, retryInterval, shardFailureBackoff, entryRestartBackoff, snapshotInterval, bufferSize, rememberEntries,
      idExtractor = {
        case msg if messageExtractor.entryId(msg) ne null ⇒
          (messageExtractor.entryId(msg), messageExtractor.entryMessage(msg))
      }: ShardRegion.IdExtractor,
      shardResolver = msg ⇒ messageExtractor.shardId(msg))
  }

  /**
   * Scala API: Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor
   * when using it in proxy only mode.
   */
  def proxyProps(
    typeName: String,
    role: Option[String],
    coordinatorPath: String,
    retryInterval: FiniteDuration,
    bufferSize: Int,
    idExtractor: ShardRegion.IdExtractor,
    shardResolver: ShardRegion.ShardResolver): Props =
    Props(new ShardRegion(typeName, None, role, coordinatorPath, retryInterval, Duration.Zero, Duration.Zero, Duration.Zero, bufferSize, false, idExtractor, shardResolver))

  /**
   * Java API: : Factory method for the [[akka.actor.Props]] of the [[ShardRegion]] actor
   * when using it in proxy only mode.
   */
  def proxyProps(
    typeName: String,
    role: String,
    coordinatorPath: String,
    retryInterval: FiniteDuration,
    bufferSize: Int,
    messageExtractor: ShardRegion.MessageExtractor): Props = {

    proxyProps(typeName, roleOption(role), coordinatorPath, retryInterval, bufferSize, idExtractor = {
      case msg if messageExtractor.entryId(msg) ne null ⇒
        (messageExtractor.entryId(msg), messageExtractor.entryMessage(msg))
    },
      shardResolver = msg ⇒ messageExtractor.shardId(msg))
  }

  /**
   * Marker type of entry identifier (`String`).
   */
  type EntryId = String
  /**
   * Marker type of shard identifier (`String`).
   */
  type ShardId = String
  /**
   * Marker type of application messages (`Any`).
   */
  type Msg = Any
  /**
   * Interface of the partial function used by the [[ShardRegion]] to
   * extract the entry id and the message to send to the entry from an
   * incoming message. The implementation is application specific.
   * If the partial function does not match the message will be
   * `unhandled`, i.e. posted as `Unhandled` messages on the event stream.
   * Note that the extracted  message does not have to be the same as the incoming
   * message to support wrapping in message envelope that is unwrapped before
   * sending to the entry actor.
   */
  type IdExtractor = PartialFunction[Msg, (EntryId, Msg)]
  /**
   * Interface of the function used by the [[ShardRegion]] to
   * extract the shard id from an incoming message.
   * Only messages that passed the [[IdExtractor]] will be used
   * as input to this function.
   */
  type ShardResolver = Msg ⇒ ShardId

  /**
   * Java API: Interface of functions to extract entry id,
   * shard id, and the message to send to the entry from an
   * incoming message.
   */
  trait MessageExtractor {
    /**
     * Extract the entry id from an incoming `message`. If `null` is returned
     * the message will be `unhandled`, i.e. posted as `Unhandled` messages on the event stream
     */
    def entryId(message: Any): String
    /**
     * Extract the message to send to the entry from an incoming `message`.
     * Note that the extracted message does not have to be the same as the incoming
     * message to support wrapping in message envelope that is unwrapped before
     * sending to the entry actor.
     */
    def entryMessage(message: Any): Any
    /**
     * Extract the entry id from an incoming `message`. Only messages that passed the [[#entryId]]
     * function will be used as input to this function.
     */
    def shardId(message: Any): String
  }

  sealed trait ShardRegionCommand

  /**
   * If the state of the entries are persistent you may stop entries that are not used to
   * reduce memory consumption. This is done by the application specific implementation of
   * the entry actors for example by defining receive timeout (`context.setReceiveTimeout`).
   * If a message is already enqueued to the entry when it stops itself the enqueued message
   * in the mailbox will be dropped. To support graceful passivation without loosing such
   * messages the entry actor can send this `Passivate` message to its parent `ShardRegion`.
   * The specified wrapped `stopMessage` will be sent back to the entry, which is
   * then supposed to stop itself. Incoming messages will be buffered by the `ShardRegion`
   * between reception of `Passivate` and termination of the entry. Such buffered messages
   * are thereafter delivered to a new incarnation of the entry.
   *
   * [[akka.actor.PoisonPill]] is a perfectly fine `stopMessage`.
   */
  @SerialVersionUID(1L) final case class Passivate(stopMessage: Any) extends ShardRegionCommand

  private case object Retry extends ShardRegionCommand

  private def roleOption(role: String): Option[String] = role match {
    case null | "" ⇒ None
    case _         ⇒ Some(role)
  }

  /**
   * INTERNAL API. Sends `PoisonPill` to the entries and when all of them have terminated
   * it replies with `ShardStopped`.
   */
  private[akka] class HandOffStopper(shard: String, replyTo: ActorRef, entries: Set[ActorRef]) extends Actor {
    import ShardCoordinator.Internal.ShardStopped

    entries.foreach { a ⇒
      context watch a
      a ! PoisonPill
    }

    var remaining = entries

    def receive = {
      case Terminated(ref) ⇒
        remaining -= ref
        if (remaining.isEmpty) {
          replyTo ! ShardStopped(shard)
          context stop self
        }
    }
  }

  private[akka] def handOffStopperProps(shard: String, replyTo: ActorRef, entries: Set[ActorRef]): Props =
    Props(new HandOffStopper(shard, replyTo, entries))
}

/**
 * This actor creates children entry actors on demand for the shards that it is told to be
 * responsible for. It delegates messages targeted to other shards to the responsible
 * `ShardRegion` actor on other nodes.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
class ShardRegion(
  typeName: String,
  entryProps: Option[Props],
  role: Option[String],
  coordinatorPath: String,
  retryInterval: FiniteDuration,
  shardFailureBackoff: FiniteDuration,
  entryRestartBackoff: FiniteDuration,
  snapshotInterval: FiniteDuration,
  bufferSize: Int,
  rememberEntries: Boolean,
  idExtractor: ShardRegion.IdExtractor,
  shardResolver: ShardRegion.ShardResolver) extends Actor with ActorLogging {

  import ShardCoordinator.Internal._
  import ShardRegion._

  val cluster = Cluster(context.system)

  // sort by age, oldest first
  val ageOrdering = Ordering.fromLessThan[Member] { (a, b) ⇒ a.isOlderThan(b) }
  var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

  var regions = Map.empty[ActorRef, Set[ShardId]]
  var regionByShard = Map.empty[ShardId, ActorRef]
  var shardBuffers = Map.empty[ShardId, Vector[(Msg, ActorRef)]]
  var shards = Map.empty[ShardId, ActorRef]
  var shardsByRef = Map.empty[ActorRef, ShardId]
  var handingOff = Set.empty[ActorRef]

  def totalBufferSize = shardBuffers.foldLeft(0) { (sum, entry) ⇒ sum + entry._2.size }

  import context.dispatcher
  val retryTask = context.system.scheduler.schedule(retryInterval, retryInterval, self, Retry)

  // subscribe to MemberEvent, re-subscribe when restart
  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster.unsubscribe(self)
    retryTask.cancel()
  }

  def matchingRole(member: Member): Boolean = role match {
    case None    ⇒ true
    case Some(r) ⇒ member.hasRole(r)
  }

  def coordinatorSelection: Option[ActorSelection] =
    membersByAge.headOption.map(m ⇒ context.actorSelection(RootActorPath(m.address) + coordinatorPath))

  var coordinator: Option[ActorRef] = None

  def changeMembers(newMembers: immutable.SortedSet[Member]): Unit = {
    val before = membersByAge.headOption
    val after = newMembers.headOption
    membersByAge = newMembers
    if (before != after) {
      if (log.isDebugEnabled)
        log.debug("Coordinator moved from [{}] to [{}]", before.map(_.address).getOrElse(""), after.map(_.address).getOrElse(""))
      coordinator = None
      register()
    }
  }

  def receive = {
    case Terminated(ref)                     ⇒ receiveTerminated(ref)
    case evt: ClusterDomainEvent             ⇒ receiveClusterEvent(evt)
    case state: CurrentClusterState          ⇒ receiveClusterState(state)
    case msg: CoordinatorMessage             ⇒ receiveCoordinatorMessage(msg)
    case cmd: ShardRegionCommand             ⇒ receiveCommand(cmd)
    case msg if idExtractor.isDefinedAt(msg) ⇒ deliverMessage(msg, sender())
  }

  def receiveClusterState(state: CurrentClusterState): Unit = {
    changeMembers(immutable.SortedSet.empty(ageOrdering) ++ state.members.filter(m ⇒
      m.status == MemberStatus.Up && matchingRole(m)))
  }

  def receiveClusterEvent(evt: ClusterDomainEvent): Unit = evt match {
    case MemberUp(m) ⇒
      if (matchingRole(m))
        changeMembers(membersByAge + m)

    case MemberRemoved(m, _) ⇒
      if (matchingRole(m))
        changeMembers(membersByAge - m)

    case _ ⇒ unhandled(evt)
  }

  def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HostShard(shard) ⇒
      log.debug("Host Shard [{}] ", shard)
      regionByShard = regionByShard.updated(shard, self)
      regions = regions.updated(self, regions.getOrElse(self, Set.empty) + shard)

      //Start the shard, if already started this does nothing
      getShard(shard)
      deliverBufferedMessages(shard)

      sender() ! ShardStarted(shard)

    case ShardHome(shard, ref) ⇒
      log.debug("Shard [{}] located at [{}]", shard, ref)
      regionByShard.get(shard) match {
        case Some(r) if r == self && ref != self ⇒
          // should not happen, inconsistency between ShardRegion and ShardCoordinator
          throw new IllegalStateException(s"Unexpected change of shard [${shard}] from self to [${ref}]")
        case _ ⇒
      }
      regionByShard = regionByShard.updated(shard, ref)
      regions = regions.updated(ref, regions.getOrElse(ref, Set.empty) + shard)

      if (ref != self)
        context.watch(ref)

      deliverBufferedMessages(shard)

    case RegisterAck(coord) ⇒
      context.watch(coord)
      coordinator = Some(coord)
      requestShardBufferHomes()

    case SnapshotTick ⇒
      context.children.foreach(_ ! SnapshotTick)

    case BeginHandOff(shard) ⇒
      log.debug("BeginHandOff shard [{}]", shard)
      if (regionByShard.contains(shard)) {
        val regionRef = regionByShard(shard)
        val updatedShards = regions(regionRef) - shard
        if (updatedShards.isEmpty) regions -= regionRef
        else regions = regions.updated(regionRef, updatedShards)
        regionByShard -= shard
      }
      sender() ! BeginHandOffAck(shard)

    case msg @ HandOff(shard) ⇒
      log.debug("HandOff shard [{}]", shard)

      // must drop requests that came in between the BeginHandOff and now,
      // because they might be forwarded from other regions and there
      // is a risk or message re-ordering otherwise
      if (shardBuffers.contains(shard))
        shardBuffers -= shard

      if (shards.contains(shard)) {
        handingOff += shards(shard)
        shards(shard) forward msg
      } else
        sender() ! ShardStopped(shard)

    case _ ⇒ unhandled(msg)

  }

  def receiveCommand(cmd: ShardRegionCommand): Unit = cmd match {
    case Retry ⇒
      if (coordinator.isEmpty)
        register()
      else
        requestShardBufferHomes()

    case _ ⇒ unhandled(cmd)
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    if (coordinator.exists(_ == ref))
      coordinator = None
    else if (regions.contains(ref)) {
      val shards = regions(ref)
      regionByShard --= shards
      regions -= ref
      if (log.isDebugEnabled)
        log.debug("Region [{}] with shards [{}] terminated", ref, shards.mkString(", "))
    } else if (shardsByRef.contains(ref)) {
      val shardId: ShardId = shardsByRef(ref)

      //Are we meant to be handing off, or is this a unknown stop?
      if (handingOff.contains(ref)) {
        shardsByRef = shardsByRef - ref
        shards = shards - shardId
        handingOff = handingOff - ref
        log.debug("Shard [{}] handoff complete", shardId)
      } else {
        throw new IllegalStateException(s"Shard [$shardId] terminated while not being handed off.")
      }
    }
  }

  def register(): Unit = {
    coordinatorSelection.foreach(_ ! registrationMessage)
  }

  def registrationMessage: Any =
    if (entryProps.isDefined) Register(self) else RegisterProxy(self)

  def requestShardBufferHomes(): Unit = {
    shardBuffers.foreach {
      case (shard, _) ⇒ coordinator.foreach { c ⇒
        log.debug("Retry request for shard [{}] homes", shard)
        c ! GetShardHome(shard)
      }
    }
  }

  def deliverBufferedMessages(shard: String): Unit = {
    shardBuffers.get(shard) match {
      case Some(buf) ⇒
        buf.foreach { case (msg, snd) ⇒ deliverMessage(msg, snd) }
        shardBuffers -= shard
      case None ⇒
    }
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val shard = shardResolver(msg)
    regionByShard.get(shard) match {
      case Some(ref) if ref == self ⇒
        getShard(shard).tell(msg, snd)
      case Some(ref) ⇒
        log.debug("Forwarding request for shard [{}] to [{}]", shard, ref)
        ref.tell(msg, snd)
      case None if (shard == null || shard == "") ⇒
        log.warning("Shard must not be empty, dropping message [{}]", msg.getClass.getName)
        context.system.deadLetters ! msg
      case None ⇒
        if (!shardBuffers.contains(shard)) {
          log.debug("Request shard [{}] home", shard)
          coordinator.foreach(_ ! GetShardHome(shard))
        }
        if (totalBufferSize >= bufferSize) {
          log.debug("Buffer is full, dropping message for shard [{}]", shard)
          context.system.deadLetters ! msg
        } else {
          val buf = shardBuffers.getOrElse(shard, Vector.empty)
          shardBuffers = shardBuffers.updated(shard, buf :+ ((msg, snd)))
        }
    }
  }

  def getShard(id: ShardId): ActorRef = shards.getOrElse(
    id,
    entryProps match {
      case Some(props) ⇒
        log.debug("Starting shard [{}] in region", id)

        val name = URLEncoder.encode(id, "utf-8")
        val shard = context.watch(context.actorOf(
          Shard.props(
            typeName,
            id,
            props,
            shardFailureBackoff,
            entryRestartBackoff,
            snapshotInterval,
            bufferSize,
            rememberEntries,
            idExtractor,
            shardResolver),
          name))
        shards = shards.updated(id, shard)
        shardsByRef = shardsByRef.updated(shard, id)
        shard
      case None ⇒
        throw new IllegalStateException("Shard must not be allocated to a proxy only ShardRegion")
    })
}

/**
 * INTERNAL API
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] object Shard {
  import ShardRegion.EntryId

  object State {
    val Empty = State()
  }

  /**
   * A Shard command
   */
  sealed trait ShardCommand

  /**
   * When a `StateChange` fails to write to the journal, we will retry it after a back
   * off.
   */
  final case class RetryPersistence(payload: StateChange) extends ShardCommand

  /**
   * The Snapshot tick for the shards
   */
  private case object SnapshotTick extends ShardCommand

  /**
   * When an remembering entries and the entry stops without issuing a `Passivate`, we
   * restart it after a back off using this message.
   */
  final case class RestartEntry(entry: EntryId) extends ShardCommand

  /**
   * A case class which represents a state change for the Shard
   */
  sealed trait StateChange { val entryId: EntryId }

  /**
   * `State` change for starting an entry in this `Shard`
   */
  @SerialVersionUID(1L) final case class EntryStarted(entryId: EntryId) extends StateChange

  /**
   * `State` change for an entry which has terminated.
   */
  @SerialVersionUID(1L) final case class EntryStopped(entryId: EntryId) extends StateChange

  /**
   * Persistent state of the Shard.
   */
  @SerialVersionUID(1L) final case class State private (
    entries: Set[EntryId] = Set.empty)

  /**
   * Factory method for the [[akka.actor.Props]] of the [[Shard]] actor.
   */
  def props(typeName: String,
            shardId: ShardRegion.ShardId,
            entryProps: Props,
            shardFailureBackoff: FiniteDuration,
            entryRestartBackoff: FiniteDuration,
            snapshotInterval: FiniteDuration,
            bufferSize: Int,
            rememberEntries: Boolean,
            idExtractor: ShardRegion.IdExtractor,
            shardResolver: ShardRegion.ShardResolver): Props =
    Props(new Shard(typeName, shardId, entryProps, shardFailureBackoff, entryRestartBackoff, snapshotInterval, bufferSize, rememberEntries, idExtractor, shardResolver))
}

/**
 * INTERNAL API
 *
 * This actor creates children entry actors on demand that it is told to be
 * responsible for.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
private[akka] class Shard(
  typeName: String,
  shardId: ShardRegion.ShardId,
  entryProps: Props,
  shardFailureBackoff: FiniteDuration,
  entryRestartBackoff: FiniteDuration,
  snapshotInterval: FiniteDuration,
  bufferSize: Int,
  rememberEntries: Boolean,
  idExtractor: ShardRegion.IdExtractor,
  shardResolver: ShardRegion.ShardResolver) extends PersistentActor with ActorLogging {

  import ShardRegion.{ handOffStopperProps, EntryId, Msg, Passivate }
  import ShardCoordinator.Internal.{ HandOff, ShardStopped }
  import Shard.{ State, RetryPersistence, RestartEntry, EntryStopped, EntryStarted, SnapshotTick }
  import akka.contrib.pattern.ShardCoordinator.Internal.CoordinatorMessage
  import akka.contrib.pattern.ShardRegion.ShardRegionCommand
  import akka.persistence.RecoveryCompleted

  import context.dispatcher
  val snapshotTask = context.system.scheduler.schedule(snapshotInterval, snapshotInterval, self, SnapshotTick)

  override def persistenceId = s"/sharding/${typeName}Shard/${shardId}"

  var state = State.Empty
  var idByRef = Map.empty[ActorRef, EntryId]
  var refById = Map.empty[EntryId, ActorRef]
  var passivating = Set.empty[ActorRef]
  var messageBuffers = Map.empty[EntryId, Vector[(Msg, ActorRef)]]

  var handOffStopper: Option[ActorRef] = None

  def totalBufferSize = messageBuffers.foldLeft(0) { (sum, entry) ⇒ sum + entry._2.size }

  def processChange[A](event: A)(handler: A ⇒ Unit): Unit =
    if (rememberEntries) persist(event)(handler)
    else handler(event)

  override def receiveRecover: Receive = {
    case EntryStarted(id) if rememberEntries ⇒ state = state.copy(state.entries + id)
    case EntryStopped(id) if rememberEntries ⇒ state = state.copy(state.entries - id)
    case SnapshotOffer(_, snapshot: State)   ⇒ state = snapshot
    case RecoveryCompleted                   ⇒ state.entries foreach getEntry
  }

  override def receiveCommand: Receive = {
    case Terminated(ref)                                ⇒ receiveTerminated(ref)
    case msg: CoordinatorMessage                        ⇒ receiveCoordinatorMessage(msg)
    case msg: ShardCommand                              ⇒ receiveShardCommand(msg)
    case msg: ShardRegionCommand                        ⇒ receiveShardRegionCommand(msg)
    case PersistenceFailure(payload: StateChange, _, _) ⇒ persistenceFailure(payload)
    case msg if idExtractor.isDefinedAt(msg)            ⇒ deliverMessage(msg, sender())
  }

  def receiveShardCommand(msg: ShardCommand): Unit = msg match {
    case SnapshotTick              ⇒ saveSnapshot(state)
    case RetryPersistence(payload) ⇒ retryPersistence(payload)
    case RestartEntry(id)          ⇒ getEntry(id)
  }

  def receiveShardRegionCommand(msg: ShardRegionCommand): Unit = msg match {
    case Passivate(stopMessage) ⇒ passivate(sender(), stopMessage)
    case _                      ⇒ unhandled(msg)
  }

  def receiveCoordinatorMessage(msg: CoordinatorMessage): Unit = msg match {
    case HandOff(`shardId`) ⇒ handOff(sender())
    case HandOff(shard)     ⇒ log.warning("Shard [{}] can not hand off for another Shard [{}]", shardId, shard)
    case _                  ⇒ unhandled(msg)
  }

  def persistenceFailure(payload: StateChange): Unit = {
    log.debug("Persistence of [{}] failed, will backoff and retry", payload)
    if (!messageBuffers.isDefinedAt(payload.entryId)) {
      messageBuffers = messageBuffers.updated(payload.entryId, Vector.empty)
    }

    context.system.scheduler.scheduleOnce(shardFailureBackoff, self, RetryPersistence(payload))
  }

  def retryPersistence(payload: StateChange): Unit = {
    log.debug("Retrying Persistence of [{}]", payload)
    persist(payload) { _ ⇒
      payload match {
        case msg: EntryStarted ⇒ sendMsgBuffer(msg)
        case msg: EntryStopped ⇒ passivateCompleted(msg)
      }
    }
  }

  def handOff(replyTo: ActorRef): Unit = handOffStopper match {
    case Some(_) ⇒ log.warning("HandOff shard [{}] received during existing handOff", shardId)
    case None ⇒
      log.debug("HandOff shard [{}]", shardId)

      if (state.entries.nonEmpty) {
        handOffStopper = Some(context.watch(context.actorOf(handOffStopperProps(shardId, replyTo, idByRef.keySet))))

        //During hand off we only care about watching for termination of the hand off stopper
        context become {
          case Terminated(ref) ⇒ receiveTerminated(ref)
        }
      } else {
        replyTo ! ShardStopped(shardId)
        context stop self
      }
  }

  def receiveTerminated(ref: ActorRef): Unit = {
    if (handOffStopper.exists(_ == ref)) {
      context stop self
    } else if (idByRef.contains(ref) && handOffStopper.isEmpty) {
      val id = idByRef(ref)
      if (messageBuffers.getOrElse(id, Vector.empty).nonEmpty) {
        //Note; because we're not persisting the EntryStopped, we don't need
        // to persist the EntryStarted either.
        log.debug("Starting entry [{}] again, there are buffered messages for it", id)
        sendMsgBuffer(EntryStarted(id))
      } else {
        if (rememberEntries && !passivating.contains(ref)) {
          log.debug("Entry [{}] stopped without passivating, will restart after backoff", id)
          context.system.scheduler.scheduleOnce(entryRestartBackoff, self, RestartEntry(id))
        } else processChange(EntryStopped(id))(passivateCompleted)
      }

      passivating = passivating - ref
    }
  }

  def passivate(entry: ActorRef, stopMessage: Any): Unit = {
    idByRef.get(entry) match {
      case Some(id) if !messageBuffers.contains(id) ⇒
        log.debug("Passivating started on entry {}", id)

        passivating = passivating + entry
        messageBuffers = messageBuffers.updated(id, Vector.empty)
        entry ! stopMessage

      case _ ⇒ //ignored
    }
  }

  // EntryStopped persistence handler
  def passivateCompleted(event: EntryStopped): Unit = {
    log.debug("Entry stopped [{}]", event.entryId)

    val ref = refById(event.entryId)
    idByRef -= ref
    refById -= event.entryId

    state = state.copy(state.entries - event.entryId)
    messageBuffers = messageBuffers - event.entryId
  }

  // EntryStarted persistence handler
  def sendMsgBuffer(event: EntryStarted): Unit = {
    //Get the buffered messages and remove the buffer
    val messages = messageBuffers.getOrElse(event.entryId, Vector.empty)
    messageBuffers = messageBuffers - event.entryId

    if (messages.nonEmpty) {
      log.debug("Sending message buffer for entry [{}] ([{}] messages)", event.entryId, messages.size)
      getEntry(event.entryId)

      //Now there is no deliveryBuffer we can try to redeliver
      // and as the child exists, the message will be directly forwarded
      messages foreach {
        case (msg, snd) ⇒ deliverMessage(msg, snd)
      }
    }
  }

  def deliverMessage(msg: Any, snd: ActorRef): Unit = {
    val (id, payload) = idExtractor(msg)
    if (id == null || id == "") {
      log.warning("Id must not be empty, dropping message [{}]", msg.getClass.getName)
      context.system.deadLetters ! msg
    } else {
      messageBuffers.get(id) match {
        case None ⇒ deliverTo(id, msg, payload, snd)

        case Some(buf) if totalBufferSize >= bufferSize ⇒
          log.debug("Buffer is full, dropping message for entry [{}]", id)
          context.system.deadLetters ! msg

        case Some(buf) ⇒
          log.debug("Message for entry [{}] buffered", id)
          messageBuffers = messageBuffers.updated(id, buf :+ ((msg, snd)))
      }
    }
  }

  def deliverTo(id: EntryId, msg: Any, payload: Msg, snd: ActorRef): Unit = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name) match {
      case Some(actor) ⇒
        actor.tell(payload, snd)

      case None if rememberEntries ⇒
        //Note; we only do this if remembering, otherwise the buffer is an overhead
        messageBuffers = messageBuffers.updated(id, Vector((msg, snd)))
        persist(EntryStarted(id))(sendMsgBuffer)

      case None ⇒
        getEntry(id).tell(payload, snd)
    }
  }

  def getEntry(id: EntryId): ActorRef = {
    val name = URLEncoder.encode(id, "utf-8")
    context.child(name).getOrElse {
      log.debug("Starting entry [{}] in shard [{}]", id, shardId)

      val a = context.watch(context.actorOf(entryProps, name))
      idByRef = idByRef.updated(a, id)
      refById = refById.updated(id, a)
      state = state.copy(state.entries + id)
      a
    }
  }
}

/**
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
object ShardCoordinatorSupervisor {
  /**
   * Factory method for the [[akka.actor.Props]] of the [[ShardCoordinator]] actor.
   */
  def props(failureBackoff: FiniteDuration, coordinatorProps: Props): Props =
    Props(new ShardCoordinatorSupervisor(failureBackoff, coordinatorProps))

  /**
   * INTERNAL API
   */
  private case object StartCoordinator
}

class ShardCoordinatorSupervisor(failureBackoff: FiniteDuration, coordinatorProps: Props) extends Actor {
  import ShardCoordinatorSupervisor._

  def startCoordinator(): Unit = {
    // it will be stopped in case of PersistenceFailure
    context.watch(context.actorOf(coordinatorProps, "coordinator"))
  }

  override def preStart(): Unit = startCoordinator()

  def receive = {
    case Terminated(_) ⇒
      import context.dispatcher
      context.system.scheduler.scheduleOnce(failureBackoff, self, StartCoordinator)
    case StartCoordinator ⇒ startCoordinator()
  }
}

/**
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
object ShardCoordinator {

  import ShardRegion.ShardId

  /**
   * Factory method for the [[akka.actor.Props]] of the [[ShardCoordinator]] actor.
   */
  def props(handOffTimeout: FiniteDuration, shardStartTimeout: FiniteDuration, rebalanceInterval: FiniteDuration, snapshotInterval: FiniteDuration,
            allocationStrategy: ShardAllocationStrategy): Props =
    Props(new ShardCoordinator(handOffTimeout, shardStartTimeout, rebalanceInterval, snapshotInterval, allocationStrategy))

  /**
   * Interface of the pluggable shard allocation and rebalancing logic used by the [[ShardCoordinator]].
   *
   * Java implementations should extend [[AbstractShardAllocationStrategy]].
   */
  trait ShardAllocationStrategy extends NoSerializationVerificationNeeded {
    /**
     * Invoked when the location of a new shard is to be decided.
     * @param requester actor reference to the [[ShardRegion]] that requested the location of the
     *   shard, can be returned if preference should be given to the node where the shard was first accessed
     * @param shardId the id of the shard to allocate
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard, must be one of
     *   the references included in the `currentShardAllocations` parameter
     */
    def allocateShard(requester: ActorRef, shardId: ShardId,
                      currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): ActorRef

    /**
     * Invoked periodically to decide which shards to rebalance to another location.
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @param rebalanceInProgress set of shards that are currently being rebalanced, i.e.
     *   you should not include these in the returned set
     * @return the shards to be migrated, may be empty to skip rebalance in this round
     */
    def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
                  rebalanceInProgress: Set[ShardId]): Set[ShardId]
  }

  /**
   * Java API: Java implementations of custom shard allocation and rebalancing logic used by the [[ShardCoordinator]]
   * should extend this abstract class and implement the two methods.
   */
  abstract class AbstractShardAllocationStrategy extends ShardAllocationStrategy {
    override final def allocateShard(requester: ActorRef, shardId: ShardId,
                                     currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): ActorRef = {
      import scala.collection.JavaConverters._
      allocateShard(requester, shardId, currentShardAllocations.asJava)
    }

    override final def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
                                 rebalanceInProgress: Set[ShardId]): Set[ShardId] = {
      import scala.collection.JavaConverters._
      rebalance(currentShardAllocations.asJava, rebalanceInProgress.asJava).asScala.toSet
    }

    /**
     * Invoked when the location of a new shard is to be decided.
     * @param requester actor reference to the [[ShardRegion]] that requested the location of the
     *   shard, can be returned if preference should be given to the node where the shard was first accessed
     * @param shardId the id of the shard to allocate
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @return the actor ref of the [[ShardRegion]] that is to be responsible for the shard, must be one of
     *   the references included in the `currentShardAllocations` parameter
     */
    def allocateShard(requester: ActorRef, shardId: String,
                      currentShardAllocations: java.util.Map[ActorRef, immutable.IndexedSeq[String]]): ActorRef

    /**
     * Invoked periodically to decide which shards to rebalance to another location.
     * @param currentShardAllocations all actor refs to `ShardRegion` and their current allocated shards,
     *   in the order they were allocated
     * @param rebalanceInProgress set of shards that are currently being rebalanced, i.e.
     *   you should not include these in the returned set
     * @return the shards to be migrated, may be empty to skip rebalance in this round
     */
    def rebalance(currentShardAllocations: java.util.Map[ActorRef, immutable.IndexedSeq[String]],
                  rebalanceInProgress: java.util.Set[String]): java.util.Set[String]
  }

  /**
   * The default implementation of [[ShardCoordinator.LeastShardAllocationStrategy]]
   * allocates new shards to the `ShardRegion` with least number of previously allocated shards.
   * It picks shards for rebalancing handoff from the `ShardRegion` with most number of previously allocated shards.
   * They will then be allocated to the `ShardRegion` with least number of previously allocated shards,
   * i.e. new members in the cluster. There is a configurable threshold of how large the difference
   * must be to begin the rebalancing. The number of ongoing rebalancing processes can be limited.
   */
  @SerialVersionUID(1L)
  class LeastShardAllocationStrategy(rebalanceThreshold: Int, maxSimultaneousRebalance: Int)
    extends ShardAllocationStrategy with Serializable {

    override def allocateShard(requester: ActorRef, shardId: ShardId,
                               currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]]): ActorRef = {
      val (regionWithLeastShards, _) = currentShardAllocations.minBy { case (_, v) ⇒ v.size }
      regionWithLeastShards
    }

    override def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardId]],
                           rebalanceInProgress: Set[ShardId]): Set[ShardId] = {
      if (rebalanceInProgress.size < maxSimultaneousRebalance) {
        val (regionWithLeastShards, leastShards) = currentShardAllocations.minBy { case (_, v) ⇒ v.size }
        val mostShards = currentShardAllocations.collect {
          case (_, v) ⇒ v.filterNot(s ⇒ rebalanceInProgress(s))
        }.maxBy(_.size)
        if (mostShards.size - leastShards.size >= rebalanceThreshold)
          Set(mostShards.head)
        else
          Set.empty
      } else Set.empty
    }
  }

  /**
   * INTERNAL API
   */
  private[akka] object Internal {
    /**
     * Messages sent to the coordinator
     */
    sealed trait CoordinatorCommand
    /**
     * Messages sent from the coordinator
     */
    sealed trait CoordinatorMessage
    /**
     * `ShardRegion` registers to `ShardCoordinator`, until it receives [[RegisterAck]]. 
     */
    @SerialVersionUID(1L) final case class Register(shardRegion: ActorRef) extends CoordinatorCommand
    /**
     * `ShardRegion` in proxy only mode registers to `ShardCoordinator`, until it receives [[RegisterAck]]. 
     */
    @SerialVersionUID(1L) final case class RegisterProxy(shardRegionProxy: ActorRef) extends CoordinatorCommand
    /**
     * Acknowledgement from `ShardCoordinator` that [[Register]] or [[RegisterProxy]] was sucessful.
     */
    @SerialVersionUID(1L) final case class RegisterAck(coordinator: ActorRef) extends CoordinatorMessage
    /**
     * Periodic message to trigger persistent snapshot
     */
    @SerialVersionUID(1L) final case object SnapshotTick extends CoordinatorMessage
    /**
     * `ShardRegion` requests the location of a shard by sending this message
     * to the `ShardCoordinator`.
     */
    @SerialVersionUID(1L) final case class GetShardHome(shard: ShardId) extends CoordinatorCommand
    /**
     * `ShardCoordinator` replies with this message for [[GetShardHome]] requests.
     */
    @SerialVersionUID(1L) final case class ShardHome(shard: ShardId, ref: ActorRef) extends CoordinatorMessage
    /**
     * `ShardCoodinator` informs a `ShardRegion` that it is hosting this shard
     */
    @SerialVersionUID(1L) final case class HostShard(shard: ShardId) extends CoordinatorMessage
    /**
     * `ShardRegion` replies with this message for [[HostShard]] requests which lead to it hosting the shard
     */
    @SerialVersionUID(1l) final case class ShardStarted(shard: ShardId) extends CoordinatorMessage
    /**
     * `ShardCoordinator` initiates rebalancing process by sending this message
     * to all registered `ShardRegion` actors (including proxy only). They are
     * supposed to discard their known location of the shard, i.e. start buffering
     * incoming messages for the shard. They reply with [[BeginHandOffAck]].
     * When all have replied the `ShardCoordinator` continues by sending
     * [[HandOff]] to the `ShardRegion` responsible for the shard.
     */
    @SerialVersionUID(1L) final case class BeginHandOff(shard: ShardId) extends CoordinatorMessage
    /**
     * Acknowledgement of [[BeginHandOff]]
     */
    @SerialVersionUID(1L) final case class BeginHandOffAck(shard: ShardId) extends CoordinatorCommand
    /**
     * When all `ShardRegion` actors have acknoledged the [[BeginHandOff]] the
     * ShardCoordinator` sends this message to the `ShardRegion` responsible for the
     * shard. The `ShardRegion` is supposed to stop all entries in that shard and when
     * all entries have terminated reply with `ShardStopped` to the `ShardCoordinator`.
     */
    @SerialVersionUID(1L) final case class HandOff(shard: ShardId) extends CoordinatorMessage
    /**
     * Reply to [[HandOff]] when all entries in the shard have been terminated.
     */
    @SerialVersionUID(1L) final case class ShardStopped(shard: ShardId) extends CoordinatorCommand

    // DomainEvents for the persistent state of the event sourced ShardCoordinator
    sealed trait DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionRegistered(region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionProxyRegistered(regionProxy: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionTerminated(region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardRegionProxyTerminated(regionProxy: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardHomeAllocated(shard: ShardId, region: ActorRef) extends DomainEvent
    @SerialVersionUID(1L) final case class ShardHomeDeallocated(shard: ShardId) extends DomainEvent

    object State {
      val empty = State()
    }

    /**
     * Persistent state of the event sourced ShardCoordinator.
     */
    @SerialVersionUID(1L) final case class State private (
      // region for each shard
      shards: Map[ShardId, ActorRef] = Map.empty,
      // shards for each region
      regions: Map[ActorRef, Vector[ShardId]] = Map.empty,
      regionProxies: Set[ActorRef] = Set.empty,
      unallocatedShards: Set[ShardId] = Set.empty) {

      def updated(event: DomainEvent): State = event match {
        case ShardRegionRegistered(region) ⇒
          require(!regions.contains(region), s"Region $region already registered: $this")
          copy(regions = regions.updated(region, Vector.empty))
        case ShardRegionProxyRegistered(proxy) ⇒
          require(!regionProxies.contains(proxy), s"Region proxy $proxy already registered: $this")
          copy(regionProxies = regionProxies + proxy)
        case ShardRegionTerminated(region) ⇒
          require(regions.contains(region), s"Terminated region $region not registered: $this")
          copy(
            regions = regions - region,
            shards = shards -- regions(region),
            unallocatedShards = unallocatedShards ++ regions(region))
        case ShardRegionProxyTerminated(proxy) ⇒
          require(regionProxies.contains(proxy), s"Terminated region proxy $proxy not registered: $this")
          copy(regionProxies = regionProxies - proxy)
        case ShardHomeAllocated(shard, region) ⇒
          require(regions.contains(region), s"Region $region not registered: $this")
          require(!shards.contains(shard), s"Shard [$shard] already allocated: $this")
          copy(
            shards = shards.updated(shard, region),
            regions = regions.updated(region, regions(region) :+ shard),
            unallocatedShards = unallocatedShards - shard)
        case ShardHomeDeallocated(shard) ⇒
          require(shards.contains(shard), s"Shard [$shard] not allocated: $this")
          val region = shards(shard)
          require(regions.contains(region), s"Region $region for shard [$shard] not registered: $this")
          copy(
            shards = shards - shard,
            regions = regions.updated(region, regions(region).filterNot(_ == shard)),
            unallocatedShards = unallocatedShards + shard)
      }
    }

  }

  /**
   * Periodic message to trigger rebalance
   */
  private case object RebalanceTick
  /**
   * End of rebalance process performed by [[RebalanceWorker]]
   */
  private final case class RebalanceDone(shard: ShardId, ok: Boolean)
  /**
   * Check if we've received a shard start request
   */
  private final case class ResendShardHost(shard: ShardId, region: ActorRef)

  /**
   * INTERNAL API. Rebalancing process is performed by this actor.
   * It sends [[BeginHandOff]] to all `ShardRegion` actors followed by
   * [[HandOff]] to the `ShardRegion` responsible for the shard.
   * When the handoff is completed it sends [[RebalanceDone]] to its
   * parent `ShardCoordinator`. If the process takes longer than the
   * `handOffTimeout` it also sends [[RebalanceDone]].
   */
  private[akka] class RebalanceWorker(shard: String, from: ActorRef, handOffTimeout: FiniteDuration,
                                      regions: Set[ActorRef]) extends Actor {
    import Internal._
    regions.foreach(_ ! BeginHandOff(shard))
    var remaining = regions

    import context.dispatcher
    context.system.scheduler.scheduleOnce(handOffTimeout, self, ReceiveTimeout)

    def receive = {
      case BeginHandOffAck(`shard`) ⇒
        remaining -= sender()
        if (remaining.isEmpty) {
          from ! HandOff(shard)
          context.become(stoppingShard, discardOld = true)
        }
      case ReceiveTimeout ⇒ done(ok = false)
    }

    def stoppingShard: Receive = {
      case ShardStopped(shard) ⇒ done(ok = true)
      case ReceiveTimeout      ⇒ done(ok = false)
    }

    def done(ok: Boolean): Unit = {
      context.parent ! RebalanceDone(shard, ok)
      context.stop(self)
    }
  }

  private[akka] def rebalanceWorkerProps(shard: String, from: ActorRef, handOffTimeout: FiniteDuration,
                                         regions: Set[ActorRef]): Props =
    Props(new RebalanceWorker(shard, from, handOffTimeout, regions))

}

/**
 * Singleton coordinator that decides where to allocate shards.
 *
 * @see [[ClusterSharding$ ClusterSharding extension]]
 */
class ShardCoordinator(handOffTimeout: FiniteDuration, shardStartTimeout: FiniteDuration, rebalanceInterval: FiniteDuration,
                       snapshotInterval: FiniteDuration, allocationStrategy: ShardCoordinator.ShardAllocationStrategy)
  extends PersistentActor with ActorLogging {
  import ShardCoordinator._
  import ShardCoordinator.Internal._
  import ShardRegion.ShardId
  import akka.actor.Cancellable

  override def persistenceId = self.path.toStringWithoutAddress

  var persistentState = State.empty
  var rebalanceInProgress = Set.empty[ShardId]
  var unAckedHostShards = Map.empty[ShardId, Cancellable]

  import context.dispatcher
  val rebalanceTask = context.system.scheduler.schedule(rebalanceInterval, rebalanceInterval, self, RebalanceTick)
  val snapshotTask = context.system.scheduler.schedule(snapshotInterval, snapshotInterval, self, SnapshotTick)

  override def postStop(): Unit = {
    super.postStop()
    rebalanceTask.cancel()
  }

  override def receiveRecover: Receive = {
    case evt: DomainEvent ⇒
      log.debug("receiveRecover {}", evt)
      evt match {
        case ShardRegionRegistered(region) ⇒
          persistentState = persistentState.updated(evt)
        case ShardRegionProxyRegistered(proxy) ⇒
          persistentState = persistentState.updated(evt)
        case ShardRegionTerminated(region) ⇒
          if (persistentState.regions.contains(region))
            persistentState = persistentState.updated(evt)
          else {
            log.debug("ShardRegionTerminated, but region {} was not registered. This inconsistency is due to that " +
              " some stored ActorRef in Akka v2.3.0 and v2.3.1 did not contain full address information. It will be " +
              "removed by later watch.", region)
          }
        case ShardRegionProxyTerminated(proxy) ⇒
          if (persistentState.regionProxies.contains(proxy))
            persistentState = persistentState.updated(evt)
        case ShardHomeAllocated(shard, region) ⇒
          persistentState = persistentState.updated(evt)
        case _: ShardHomeDeallocated ⇒
          persistentState = persistentState.updated(evt)
      }

    case SnapshotOffer(_, state: State) ⇒
      log.debug("receiveRecover SnapshotOffer {}", state)
      //Old versions of the state object may not have unallocatedShard set,
      // thus it will be null.
      if (state.unallocatedShards == null)
        persistentState = state.copy(unallocatedShards = Set.empty)
      else
        persistentState = state

    case RecoveryCompleted ⇒
      persistentState.regionProxies.foreach(context.watch)
      persistentState.regions.foreach { case (a, _) ⇒ context.watch(a) }
      persistentState.shards.foreach { case (a, r) ⇒ sendHostShardMsg(a, r) }
      allocateShardHomes()
  }

  override def receiveCommand: Receive = {
    case Register(region) ⇒
      log.debug("ShardRegion registered: [{}]", region)
      if (persistentState.regions.contains(region))
        sender() ! RegisterAck(self)
      else
        persist(ShardRegionRegistered(region)) { evt ⇒
          val firstRegion = persistentState.regions.isEmpty

          persistentState = persistentState.updated(evt)
          context.watch(region)
          sender() ! RegisterAck(self)

          if (firstRegion)
            allocateShardHomes()
        }

    case RegisterProxy(proxy) ⇒
      log.debug("ShardRegion proxy registered: [{}]", proxy)
      if (persistentState.regionProxies.contains(proxy))
        sender() ! RegisterAck(self)
      else
        persist(ShardRegionProxyRegistered(proxy)) { evt ⇒
          persistentState = persistentState.updated(evt)
          context.watch(proxy)
          sender() ! RegisterAck(self)
        }

    case Terminated(ref) ⇒
      if (persistentState.regions.contains(ref)) {
        log.debug("ShardRegion terminated: [{}]", ref)

        require(persistentState.regions.contains(ref), s"Terminated region $ref not registered")
        persistentState.regions(ref).foreach { s ⇒ self ! GetShardHome(s) }

        persist(ShardRegionTerminated(ref)) { evt ⇒
          persistentState = persistentState.updated(evt)
          allocateShardHomes()
        }
      } else if (persistentState.regionProxies.contains(ref)) {
        log.debug("ShardRegion proxy terminated: [{}]", ref)
        persist(ShardRegionProxyTerminated(ref)) { evt ⇒
          persistentState = persistentState.updated(evt)
        }
      }

    case GetShardHome(shard) ⇒
      if (!rebalanceInProgress.contains(shard)) {
        persistentState.shards.get(shard) match {
          case Some(ref) ⇒ sender() ! ShardHome(shard, ref)
          case None ⇒
            if (persistentState.regions.nonEmpty) {
              val region = allocationStrategy.allocateShard(sender(), shard, persistentState.regions)
              require(persistentState.regions.contains(region),
                s"Allocated region $region for shard [$shard] must be one of the registered regions: $persistentState")
              persist(ShardHomeAllocated(shard, region)) { evt ⇒
                persistentState = persistentState.updated(evt)
                log.debug("Shard [{}] allocated at [{}]", evt.shard, evt.region)

                sendHostShardMsg(evt.shard, evt.region)
                sender() ! ShardHome(evt.shard, evt.region)
              }
            }
        }
      }

    case ShardStarted(shard) ⇒
      unAckedHostShards.get(shard) match {
        case Some(cancel) ⇒
          cancel.cancel()
          unAckedHostShards = unAckedHostShards - shard
        case _ ⇒
      }

    case ResendShardHost(shard, region) ⇒
      persistentState.shards.get(shard) match {
        case Some(`region`) ⇒ sendHostShardMsg(shard, region)
        case _              ⇒ //Reallocated to another region
      }

    case RebalanceTick ⇒
      if (persistentState.regions.nonEmpty)
        allocationStrategy.rebalance(persistentState.regions, rebalanceInProgress).foreach { shard ⇒
          rebalanceInProgress += shard
          val rebalanceFromRegion = persistentState.shards(shard)
          log.debug("Rebalance shard [{}] from [{}]", shard, rebalanceFromRegion)
          context.actorOf(rebalanceWorkerProps(shard, rebalanceFromRegion, handOffTimeout,
            persistentState.regions.keySet ++ persistentState.regionProxies))
        }

    case RebalanceDone(shard, ok) ⇒
      rebalanceInProgress -= shard
      log.debug("Rebalance shard [{}] done [{}]", shard, ok)
      // The shard could have been removed by ShardRegionTerminated
      if (ok && persistentState.shards.contains(shard))
        persist(ShardHomeDeallocated(shard)) { evt ⇒
          persistentState = persistentState.updated(evt)
          log.debug("Shard [{}] deallocated", evt.shard)
          allocateShardHomes()
        }

    case SnapshotTick ⇒
      log.debug("Saving persistent snapshot")
      saveSnapshot(persistentState)

    case SaveSnapshotSuccess(_) ⇒
      log.debug("Persistent snapshot saved successfully")

    case SaveSnapshotFailure(_, reason) ⇒
      log.warning("Persistent snapshot failure: {}", reason.getMessage)

    case ShardHome(_, _) ⇒
    //On rebalance, we send ourselves a GetShardHome message to reallocate a
    // shard. This recieve handles the "response" from that message. i.e. Ingores it.

  }

  def sendHostShardMsg(shard: ShardId, region: ActorRef): Unit = {
    region ! HostShard(shard)
    val cancel = context.system.scheduler.scheduleOnce(shardStartTimeout, self, ResendShardHost(shard, region))
    unAckedHostShards = unAckedHostShards.updated(shard, cancel)
  }

  def allocateShardHomes(): Unit = persistentState.unallocatedShards.foreach { self ! GetShardHome(_) }

}
