package org.bigbluebutton.core.apps.users

import org.bigbluebutton.common2.msgs._
import org.bigbluebutton.core.domain.{ MeetingExpiryTracker, MeetingState2x }
import org.bigbluebutton.core.models.{ Users2x, VoiceUserState, VoiceUsers }
import org.bigbluebutton.core.running.{ LiveMeeting, MeetingActor, OutMsgRouter }
import org.bigbluebutton.core.util.TimeUtil
import org.bigbluebutton.core2.MeetingStatus2x
import org.bigbluebutton.core2.message.senders.MsgBuilder

trait UserLeaveReqMsgHdlr {
  this: MeetingActor =>

  val outGW: OutMsgRouter

  def handleUserLeaveReqMsg(msg: UserLeaveReqMsg, state: MeetingState2x): MeetingState2x = {
    for {
      u <- Users2x.remove(liveMeeting.users2x, msg.body.userId)
    } yield {
      log.info("User left meeting. meetingId=" + props.meetingProp.intId + " userId=" + u.intId + " user=" + u)

      captionApp2x.handleUserLeavingMsg(msg.body.userId)
      stopAutoStartedRecording()

      // send a user left event for the clients to update
      val userLeftMeetingEvent = MsgBuilder.buildUserLeftMeetingEvtMsg(liveMeeting.props.meetingProp.intId, u.intId)
      outGW.send(userLeftMeetingEvent)

      if (u.presenter) {
        automaticallyAssignPresenter(outGW, liveMeeting)

        // request screenshare to end
        screenshareApp2x.handleScreenshareStoppedVoiceConfEvtMsg(liveMeeting.props.voiceProp.voiceConf, liveMeeting.props.screenshareProps.screenshareConf)

        // request ongoing poll to end
        handleStopPollReqMsg(u.intId)
      }

      def broadcastEvent(vu: VoiceUserState): Unit = {
        val routing = Routing.addMsgToClientRouting(MessageTypes.BROADCAST_TO_MEETING, liveMeeting.props.meetingProp.intId,
          vu.intId)
        val envelope = BbbCoreEnvelope(UserLeftVoiceConfToClientEvtMsg.NAME, routing)
        val header = BbbClientMsgHeader(UserLeftVoiceConfToClientEvtMsg.NAME, liveMeeting.props.meetingProp.intId, vu.intId)

        val body = UserLeftVoiceConfToClientEvtMsgBody(voiceConf = liveMeeting.props.voiceProp.voiceConf, intId = vu.intId, voiceUserId = vu.intId)

        val event = UserLeftVoiceConfToClientEvtMsg(header, body)
        val msgEvent = BbbCommonEnvCoreMsg(envelope, event)
        outGW.send(msgEvent)
      }

      for {
        user <- VoiceUsers.findWithIntId(liveMeeting.voiceUsers, msg.body.userId)
      } yield {
        VoiceUsers.removeWithIntId(liveMeeting.voiceUsers, user.intId)
        broadcastEvent(user)
      }
    }

    if (Users2x.numUsers(liveMeeting.users2x) == 0) {
      val tracker = state.expiryTracker.setLastUserLeftOn(TimeUtil.timeNowInMs())
      state.update(tracker)
    } else {
      state
    }
  }

}