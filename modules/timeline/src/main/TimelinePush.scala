package lila.timeline

import akka.actor.*

import lila.core.actorApi.timeline.{ Atom, Propagate, Propagation, ReloadTimelines }
import lila.security.Permission
import lila.user.UserRepo
import lila.core.team.Access

final private[timeline] class TimelinePush(
    relationApi: lila.core.relation.RelationApi,
    userRepo: UserRepo,
    entryApi: EntryApi,
    unsubApi: UnsubApi,
    teamApi: lila.core.team.TeamApi
) extends Actor:

  private given Executor = context.dispatcher

  private val dedup = lila.memo.OnceEvery.hashCode[Atom](10 minutes)

  def receive = { case Propagate(data, propagations) =>
    if dedup(data) then
      propagate(propagations)
        .flatMap: users =>
          unsubApi.filterUnsub(data.channel, users)
        .foreach: users =>
          if users.nonEmpty then
            insertEntry(users, data).andDo(lila.common.Bus.publish(ReloadTimelines(users), "lobbySocket"))
          lila.mon.timeline.notification.increment(users.size)
  }

  private def propagate(propagations: List[Propagation]): Fu[List[UserId]] =
    Future
      .traverse(propagations):
        case Propagation.Users(ids)    => fuccess(ids)
        case Propagation.Followers(id) => relationApi.freshFollowersFromSecondary(id)
        case Propagation.Friends(id)   => relationApi.fetchFriends(id)
        case Propagation.WithTeam(_)   => fuccess(Nil)
        case Propagation.ExceptUser(_) => fuccess(Nil)
        case Propagation.ModsOnly(_)   => fuccess(Nil)
      .flatMap: users =>
        propagations.foldLeft(fuccess(users.flatten.distinct)) {
          case (fus, Propagation.ExceptUser(id)) => fus.dmap(_.filter(id !=))
          case (fus, Propagation.ModsOnly(true)) =>
            fus.flatMap: us =>
              userRepo.userIdsWithRoles(modPermissions.map(_.dbKey)).dmap { userIds =>
                us.filter(userIds.contains)
              }
          case (fus, Propagation.WithTeam(teamId)) =>
            teamApi
              .forumAccessOf(teamId)
              .flatMap:
                case Access.Members =>
                  fus.flatMap: us =>
                    teamApi.filterUserIdsInTeam(teamId, us).map(_.toList)
                case Access.Leaders =>
                  fus.flatMap: us =>
                    teamApi.leaderIds(teamId).map(us.toSet.intersect).map(_.toList)
                case _ => fus
          case (fus, _) => fus
        }

  private def modPermissions =
    List(
      Permission.ModNote,
      Permission.Admin,
      Permission.SuperAdmin
    )

  private def insertEntry(users: List[UserId], data: Atom): Funit =
    entryApi.insert(Entry.ForUsers(Entry.make(data), users))
