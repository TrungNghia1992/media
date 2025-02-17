/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.session;

import static androidx.media3.session.MediaTestUtils.assertTimelineMediaItemsEquals;
import static androidx.media3.session.MediaUtils.createPlayerCommandsWith;
import static androidx.media3.session.MediaUtils.createPlayerCommandsWithout;
import static androidx.media3.test.session.common.CommonConstants.DEFAULT_TEST_NAME;
import static androidx.media3.test.session.common.CommonConstants.SUPPORT_APP_PACKAGE_NAME;
import static androidx.media3.test.session.common.MediaSessionConstants.KEY_AVAILABLE_SESSION_COMMANDS;
import static androidx.media3.test.session.common.MediaSessionConstants.TEST_GET_SESSION_ACTIVITY;
import static androidx.media3.test.session.common.MediaSessionConstants.TEST_IS_SESSION_COMMAND_AVAILABLE;
import static androidx.media3.test.session.common.TestUtils.LONG_TIMEOUT_MS;
import static androidx.media3.test.session.common.TestUtils.TIMEOUT_MS;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.PendingIntent;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.HeartRating;
import androidx.media3.common.IllegalSeekPositionException;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaLibraryInfo;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Player.RepeatMode;
import androidx.media3.common.Rating;
import androidx.media3.common.StarRating;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Util;
import androidx.media3.test.session.common.HandlerThreadTestRule;
import androidx.media3.test.session.common.MainLooperTestRule;
import androidx.media3.test.session.common.PollingCheck;
import androidx.media3.test.session.common.TestUtils;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/** Tests for {@link MediaController}. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class MediaControllerTest {

  private static final String TAG = "MediaControllerTest";

  @ClassRule public static MainLooperTestRule mainLooperTestRule = new MainLooperTestRule();

  private final HandlerThreadTestRule threadTestRule = new HandlerThreadTestRule(TAG);
  final MediaControllerTestRule controllerTestRule = new MediaControllerTestRule(threadTestRule);

  @Rule
  public final TestRule chain = RuleChain.outerRule(threadTestRule).around(controllerTestRule);

  private final List<RemoteMediaSession> remoteSessionList = new ArrayList<>();

  private Context context;
  private RemoteMediaSession remoteSession;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    remoteSession = createRemoteMediaSession(DEFAULT_TEST_NAME, null);
  }

  @After
  public void cleanUp() throws RemoteException {
    for (int i = 0; i < remoteSessionList.size(); i++) {
      RemoteMediaSession session = remoteSessionList.get(i);
      if (session != null) {
        session.cleanUp();
      }
    }
  }

  @Test
  public void builder() throws Exception {
    MediaController.Builder builder;
    SessionToken token = remoteSession.getToken();

    try {
      builder = new MediaController.Builder(null, token);
      assertWithMessage("null context shouldn't be allowed").fail();
    } catch (NullPointerException e) {
      // expected. pass-through
    }

    try {
      builder = new MediaController.Builder(context, null);
      assertWithMessage("null token shouldn't be allowed").fail();
    } catch (NullPointerException e) {
      // expected. pass-through
    }

    try {
      builder = new MediaController.Builder(context, token).setListener(null);
      assertWithMessage("null listener shouldn't be allowed").fail();
    } catch (NullPointerException e) {
      // expected. pass-through
    }

    try {
      builder = new MediaController.Builder(context, token).setApplicationLooper(null);
      assertWithMessage("null looper shouldn't be allowed").fail();
    } catch (NullPointerException e) {
      // expected. pass-through
    }
  }

  @Test
  public void getSessionActivity() throws Exception {
    RemoteMediaSession session = createRemoteMediaSession(TEST_GET_SESSION_ACTIVITY, null);

    MediaController controller = controllerTestRule.createController(session.getToken());
    PendingIntent sessionActivity = controller.getSessionActivity();
    assertThat(sessionActivity).isNotNull();
    if (Util.SDK_INT >= 17) {
      // PendingIntent#getCreatorPackage() is added in API 17.
      assertThat(sessionActivity.getCreatorPackage()).isEqualTo(SUPPORT_APP_PACKAGE_NAME);

      // TODO: Add getPid/getUid in MediaControllerProviderService and compare them.
      // assertThat(sessionActivity.getCreatorUid()).isEqualTo(remoteSession.getUid());
    }
    session.cleanUp();
  }

  @Test
  public void getPackageName() throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    assertThat(controller.getConnectedToken().getPackageName()).isEqualTo(SUPPORT_APP_PACKAGE_NAME);
  }

  @Test
  public void getSessionVersion() throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    // TODO(b/199226670): The expected version should vary if the test runs with the previous
    //  version of remote session.
    assertThat(controller.getConnectedToken().getSessionVersion())
        .isEqualTo(MediaLibraryInfo.VERSION_INT);
  }

  @Test
  public void getTokenExtras() throws Exception {
    Bundle testTokenExtras = TestUtils.createTestBundle();
    RemoteMediaSession session = createRemoteMediaSession("testGetExtras", testTokenExtras);

    MediaController controller = controllerTestRule.createController(session.getToken());
    SessionToken connectedToken = controller.getConnectedToken();
    assertThat(connectedToken).isNotNull();
    assertThat(TestUtils.equals(testTokenExtras, connectedToken.getExtras())).isTrue();
  }

  @Test
  public void isConnected_afterConnection_returnsTrue() throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    assertThat(controller.isConnected()).isTrue();
  }

  @Test
  public void isConnected_afterDisconnectionBySessionRelease_returnsFalse() throws Exception {
    CountDownLatch disconnectedLatch = new CountDownLatch(1);
    MediaController controller =
        controllerTestRule.createController(
            remoteSession.getToken(),
            null,
            new MediaController.Listener() {
              @Override
              public void onDisconnected(MediaController controller) {
                disconnectedLatch.countDown();
              }
            });
    remoteSession.release();

    assertThat(disconnectedLatch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(controller.isConnected()).isFalse();
  }

  @Test
  public void isConnected_afterDisconnectionByControllerRelease_returnsFalse() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    MediaController controller =
        controllerTestRule.createController(
            remoteSession.getToken(),
            /* connectionHints= */ null,
            new MediaController.Listener() {
              @Override
              public void onDisconnected(MediaController controller) {
                latch.countDown();
              }
            });
    threadTestRule.getHandler().postAndSync(controller::release);
    assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(controller.isConnected()).isFalse();
  }

  @Test
  public void isConnected_afterDisconnectionByControllerReleaseRightAfterCreated_returnsFalse()
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    MediaController controller =
        controllerTestRule.createController(
            remoteSession.getToken(),
            /* connectionHints= */ null,
            new MediaController.Listener() {
              @Override
              public void onDisconnected(MediaController controller) {
                latch.countDown();
              }
            },
            /* controllerCreationListener= */ MediaController::release);
    assertThat(latch.await(TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(controller.isConnected()).isFalse();
  }

  @Test
  public void close_twice() throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    threadTestRule.getHandler().postAndSync(controller::release);
    threadTestRule.getHandler().postAndSync(controller::release);
  }

  @Test
  public void gettersAfterConnected() throws Exception {
    long currentPositionMs = 11;
    long contentPositionMs = 33;
    long durationMs = 200;
    long bufferedPositionMs = 100;
    int bufferedPercentage = 50;
    long totalBufferedDurationMs = 120;
    long currentLiveOffsetMs = 10;
    long contentDurationMs = 300;
    long contentBufferedPositionMs = 240;
    boolean isPlayingAd = true;
    int currentAdGroupIndex = 33;
    int currentAdIndexInAdGroup = 22;
    PlaybackParameters playbackParameters = new PlaybackParameters(/* speed= */ 0.5f);
    boolean playWhenReady = true;
    @Player.PlaybackSuppressionReason
    int playbackSuppressionReason = Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS;
    @Player.State int playbackState = Player.STATE_READY;
    boolean isLoading = true;
    boolean isShuffleModeEnabled = true;
    @RepeatMode int repeatMode = Player.REPEAT_MODE_ONE;
    long seekBackIncrementMs = 1_000;
    long seekForwardIncrementMs = 2_000;
    long maxSeekToPreviousPositionMs = 300;
    ImmutableList<Tracks.Group> trackGroups =
        new ImmutableList.Builder<Tracks.Group>()
            .add(
                new Tracks.Group(
                    new TrackGroup(new Format.Builder().setChannelCount(2).build()),
                    /* adaptiveSupported= */ false,
                    /* trackSupport= */ new int[1],
                    /* trackSelected= */ new boolean[1]))
            .add(
                new Tracks.Group(
                    new TrackGroup(new Format.Builder().setHeight(1024).build()),
                    /* adaptiveSupported= */ false,
                    /* trackSupport= */ new int[1],
                    /* trackSelected= */ new boolean[1]))
            .build();
    Tracks currentTracks = new Tracks(trackGroups);
    TrackSelectionParameters trackSelectionParameters =
        TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon().setMaxVideoSizeSd().build();
    Timeline timeline = MediaTestUtils.createTimeline(5);
    int currentMediaItemIndex = 3;
    MediaItem currentMediaItem =
        timeline.getWindow(currentMediaItemIndex, new Timeline.Window()).mediaItem;

    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setCurrentPosition(currentPositionMs)
            .setContentPosition(contentPositionMs)
            .setDuration(durationMs)
            .setBufferedPosition(bufferedPositionMs)
            .setBufferedPercentage(bufferedPercentage)
            .setTotalBufferedDuration(totalBufferedDurationMs)
            .setCurrentLiveOffset(currentLiveOffsetMs)
            .setContentDuration(contentDurationMs)
            .setContentBufferedPosition(contentBufferedPositionMs)
            .setIsPlayingAd(isPlayingAd)
            .setCurrentAdGroupIndex(currentAdGroupIndex)
            .setCurrentAdIndexInAdGroup(currentAdIndexInAdGroup)
            .setPlaybackParameters(playbackParameters)
            .setPlayWhenReady(playWhenReady)
            .setPlaybackSuppressionReason(playbackSuppressionReason)
            .setPlaybackState(playbackState)
            .setIsLoading(isLoading)
            .setShuffleModeEnabled(isShuffleModeEnabled)
            .setRepeatMode(repeatMode)
            .setSeekBackIncrement(seekBackIncrementMs)
            .setSeekForwardIncrement(seekForwardIncrementMs)
            .setMaxSeekToPreviousPositionMs(maxSeekToPreviousPositionMs)
            .setTrackSelectionParameters(trackSelectionParameters)
            .setCurrentTracks(currentTracks)
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(currentMediaItemIndex)
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    threadTestRule.getHandler().postAndSync(() -> controller.setTimeDiffMs(0L));

    AtomicLong currentPositionMsRef = new AtomicLong();
    AtomicLong contentPositionMsRef = new AtomicLong();
    AtomicLong durationMsRef = new AtomicLong();
    AtomicLong bufferedPositionMsRef = new AtomicLong();
    AtomicInteger bufferedPercentageRef = new AtomicInteger();
    AtomicLong totalBufferedDurationMsRef = new AtomicLong();
    AtomicLong currentLiveOffsetMsRef = new AtomicLong();
    AtomicLong contentDurationMsRef = new AtomicLong();
    AtomicLong contentBufferedPositionMsRef = new AtomicLong();
    AtomicBoolean isPlayingAdRef = new AtomicBoolean();
    AtomicInteger currentAdGroupIndexRef = new AtomicInteger();
    AtomicInteger currentAdIndexInAdGroupRef = new AtomicInteger();
    AtomicReference<PlaybackParameters> playbackParametersRef = new AtomicReference<>();
    AtomicReference<MediaItem> mediaItemRef = new AtomicReference<>();
    AtomicBoolean playWhenReadyRef = new AtomicBoolean();
    AtomicInteger playbackSuppressionReasonRef = new AtomicInteger();
    AtomicInteger playbackStateRef = new AtomicInteger();
    AtomicBoolean isLoadingRef = new AtomicBoolean();
    AtomicBoolean isShuffleModeEnabledRef = new AtomicBoolean();
    AtomicInteger repeatModeRef = new AtomicInteger();
    AtomicLong seekBackIncrementRef = new AtomicLong();
    AtomicLong seekForwardIncrementRef = new AtomicLong();
    AtomicLong maxSeekToPreviousPositionMsRef = new AtomicLong();
    AtomicReference<Tracks> currentTracksRef = new AtomicReference<>();
    AtomicReference<TrackSelectionParameters> trackSelectionParametersRef = new AtomicReference<>();
    AtomicReference<Timeline> timelineRef = new AtomicReference<>();
    AtomicInteger currentMediaItemIndexRef = new AtomicInteger();
    AtomicReference<MediaItem> currentMediaItemRef = new AtomicReference<>();
    threadTestRule
        .getHandler()
        .postAndSync(
            () -> {
              currentPositionMsRef.set(controller.getCurrentPosition());
              contentPositionMsRef.set(controller.getContentPosition());
              durationMsRef.set(controller.getDuration());
              bufferedPositionMsRef.set(controller.getBufferedPosition());
              bufferedPercentageRef.set(controller.getBufferedPercentage());
              totalBufferedDurationMsRef.set(controller.getTotalBufferedDuration());
              currentLiveOffsetMsRef.set(controller.getCurrentLiveOffset());
              contentDurationMsRef.set(controller.getContentDuration());
              contentBufferedPositionMsRef.set(controller.getContentBufferedPosition());
              playbackParametersRef.set(controller.getPlaybackParameters());
              isPlayingAdRef.set(controller.isPlayingAd());
              currentAdGroupIndexRef.set(controller.getCurrentAdGroupIndex());
              currentAdIndexInAdGroupRef.set(controller.getCurrentAdIndexInAdGroup());
              mediaItemRef.set(controller.getCurrentMediaItem());
              playWhenReadyRef.set(controller.getPlayWhenReady());
              playbackSuppressionReasonRef.set(controller.getPlaybackSuppressionReason());
              playbackStateRef.set(controller.getPlaybackState());
              isLoadingRef.set(controller.isLoading());
              isShuffleModeEnabledRef.set(controller.getShuffleModeEnabled());
              repeatModeRef.set(controller.getRepeatMode());
              seekBackIncrementRef.set(controller.getSeekBackIncrement());
              seekForwardIncrementRef.set(controller.getSeekForwardIncrement());
              maxSeekToPreviousPositionMsRef.set(controller.getMaxSeekToPreviousPosition());
              currentTracksRef.set(controller.getCurrentTracks());
              trackSelectionParametersRef.set(controller.getTrackSelectionParameters());
              timelineRef.set(controller.getCurrentTimeline());
              currentMediaItemIndexRef.set(controller.getCurrentMediaItemIndex());
              currentMediaItemRef.set(controller.getCurrentMediaItem());
            });

    assertThat(currentPositionMsRef.get()).isEqualTo(currentPositionMs);
    assertThat(contentPositionMsRef.get()).isEqualTo(contentPositionMs);
    assertThat(durationMsRef.get()).isEqualTo(durationMs);
    assertThat(bufferedPositionMsRef.get()).isEqualTo(bufferedPositionMs);
    assertThat(bufferedPercentageRef.get()).isEqualTo(bufferedPercentage);
    assertThat(totalBufferedDurationMsRef.get()).isEqualTo(totalBufferedDurationMs);
    assertThat(currentLiveOffsetMsRef.get()).isEqualTo(currentLiveOffsetMs);
    assertThat(contentDurationMsRef.get()).isEqualTo(contentDurationMs);
    assertThat(contentBufferedPositionMsRef.get()).isEqualTo(contentBufferedPositionMs);
    assertThat(playbackParametersRef.get()).isEqualTo(playbackParameters);
    assertThat(isPlayingAdRef.get()).isEqualTo(isPlayingAd);
    assertThat(currentAdGroupIndexRef.get()).isEqualTo(currentAdGroupIndex);
    assertThat(currentAdIndexInAdGroupRef.get()).isEqualTo(currentAdIndexInAdGroup);
    assertThat(playWhenReadyRef.get()).isEqualTo(playWhenReady);
    assertThat(playbackSuppressionReasonRef.get()).isEqualTo(playbackSuppressionReason);
    assertThat(playbackStateRef.get()).isEqualTo(playbackState);
    assertThat(isLoadingRef.get()).isEqualTo(isLoading);
    assertThat(isShuffleModeEnabledRef.get()).isEqualTo(isShuffleModeEnabled);
    assertThat(repeatModeRef.get()).isEqualTo(repeatMode);
    assertThat(seekBackIncrementRef.get()).isEqualTo(seekBackIncrementMs);
    assertThat(seekForwardIncrementRef.get()).isEqualTo(seekForwardIncrementMs);
    assertThat(maxSeekToPreviousPositionMsRef.get()).isEqualTo(maxSeekToPreviousPositionMs);
    assertThat(trackSelectionParametersRef.get()).isEqualTo(trackSelectionParameters);
    assertThat(currentTracksRef.get()).isEqualTo(currentTracks);
    assertTimelineMediaItemsEquals(timelineRef.get(), timeline);
    assertThat(currentMediaItemIndexRef.get()).isEqualTo(currentMediaItemIndex);
    assertThat(currentMediaItemRef.get()).isEqualTo(currentMediaItem);
  }

  @Test
  public void getPlayerError() throws Exception {
    PlaybackException testPlayerError =
        new PlaybackException(
            /* message= */ "test", /* cause= */ null, PlaybackException.ERROR_CODE_REMOTE_ERROR);

    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder().setPlayerError(testPlayerError).build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    PlaybackException playerError =
        threadTestRule.getHandler().postAndSync(controller::getPlayerError);
    assertThat(TestUtils.equals(playerError, testPlayerError)).isTrue();
  }

  @Test
  public void getVideoSize_returnsVideoSizeOfPlayerInSession() throws Exception {
    VideoSize testVideoSize =
        new VideoSize(
            /* width= */ 100,
            /* height= */ 42,
            /* unappliedRotationDegrees= */ 90,
            /* pixelWidthHeightRatio= */ 1.2f);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder().setVideoSize(testVideoSize).build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    VideoSize videoSize = threadTestRule.getHandler().postAndSync(controller::getVideoSize);
    assertThat(videoSize).isEqualTo(testVideoSize);
  }

  @Test
  public void futuresCompleted_AvailableCommandsChange() throws Exception {
    RemoteMediaSession session = remoteSession;
    MediaController controller = controllerTestRule.createController(session.getToken());

    SessionCommands.Builder builder = new SessionCommands.Builder();
    SessionCommand setRatingCommand =
        new SessionCommand(SessionCommand.COMMAND_CODE_SESSION_SET_RATING);
    SessionCommand customCommand = new SessionCommand("custom", /* extras= */ Bundle.EMPTY);

    int trials = 100;
    CountDownLatch latch = new CountDownLatch(trials * 2);

    for (int trial = 0; trial < trials; trial++) {
      if (trial % 2 == 0) {
        builder.add(setRatingCommand);
        builder.add(customCommand);
      } else {
        builder.remove(setRatingCommand);
        builder.remove(customCommand);
      }
      session.setAvailableCommands(builder.build(), Player.Commands.EMPTY);

      String testMediaId = "testMediaId";
      Rating testRating = new HeartRating(/* hasHeart= */ true);
      threadTestRule
          .getHandler()
          .postAndSync(
              () -> {
                controller
                    .setRating(testMediaId, testRating)
                    .addListener(latch::countDown, Runnable::run);
                controller
                    .sendCustomCommand(customCommand, /* args= */ Bundle.EMPTY)
                    .addListener(latch::countDown, Runnable::run);
              });
    }

    assertWithMessage("All futures should be completed")
        .that(latch.await(LONG_TIMEOUT_MS, MILLISECONDS))
        .isTrue();
  }

  @Test
  public void getPlaylistMetadata_returnsPlaylistMetadataOfPlayerInSession() throws Exception {
    MediaMetadata playlistMetadata = new MediaMetadata.Builder().setTitle("title").build();
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setPlaylistMetadata(playlistMetadata)
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(threadTestRule.getHandler().postAndSync(controller::getPlaylistMetadata))
        .isEqualTo(playlistMetadata);
  }

  @Test
  public void getTrackSelectionParameters_returnsTrackSelectionParametersOfPlayerInSession()
      throws Exception {
    TrackSelectionParameters parameters =
        TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon().setMaxVideoSizeSd().build();
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTrackSelectionParameters(parameters)
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(threadTestRule.getHandler().postAndSync(controller::getTrackSelectionParameters))
        .isEqualTo(parameters);
  }

  @Test
  public void getAudioAttributes_returnsAudioAttributesOfPlayerInSession() throws Exception {
    AudioAttributes testAttributes =
        new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build();

    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder().setAudioAttributes(testAttributes).build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    AudioAttributes attributes =
        threadTestRule.getHandler().postAndSync(controller::getAudioAttributes);
    assertThat(attributes).isEqualTo(testAttributes);
  }

  @Test
  public void getVolume_returnsVolumeOfPlayerInSession() throws Exception {
    float testVolume = .5f;

    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder().setVolume(testVolume).build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    float volume = threadTestRule.getHandler().postAndSync(controller::getVolume);
    assertThat(volume).isEqualTo(testVolume);
  }

  @Test
  public void getCurrentMediaItemIndex() throws Exception {
    int testMediaItemIndex = 1;
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setCurrentMediaItemIndex(testMediaItemIndex)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int currentMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getCurrentMediaItemIndex);

    assertThat(currentMediaItemIndex).isEqualTo(testMediaItemIndex);
  }

  @Test
  public void getCurrentPeriodIndex() throws Exception {
    int testPeriodIndex = 1;
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setCurrentPeriodIndex(testPeriodIndex)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int currentPeriodIndex =
        threadTestRule.getHandler().postAndSync(controller::getCurrentPeriodIndex);

    assertThat(currentPeriodIndex).isEqualTo(testPeriodIndex);
  }

  @Test
  public void getPreviousMediaItemIndex() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(1)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int previousMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getPreviousMediaItemIndex);

    assertThat(previousMediaItemIndex).isEqualTo(0);
  }

  @Test
  public void getPreviousMediaItemIndex_withRepeatModeOne() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(1)
            .setRepeatMode(Player.REPEAT_MODE_ONE)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int previousMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getPreviousMediaItemIndex);

    assertThat(previousMediaItemIndex).isEqualTo(0);
  }

  @Test
  public void getPreviousMediaItemIndex_atTheFirstMediaItem() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(0)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int previousMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getPreviousMediaItemIndex);

    assertThat(previousMediaItemIndex).isEqualTo(C.INDEX_UNSET);
  }

  @Test
  public void getPreviousMediaItemIndex_atTheFirstMediaItemWithRepeatModeAll() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(0)
            .setRepeatMode(Player.REPEAT_MODE_ALL)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int previousMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getPreviousMediaItemIndex);

    assertThat(previousMediaItemIndex).isEqualTo(2);
  }

  @Test
  public void getPreviousMediaItemIndex_withShuffleModeEnabled() throws Exception {
    Timeline timeline =
        new PlaylistTimeline(
            MediaTestUtils.createMediaItems(/* size= */ 3),
            /* shuffledIndices= */ new int[] {0, 2, 1});
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(2)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(true)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int previousMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getPreviousMediaItemIndex);

    assertThat(previousMediaItemIndex).isEqualTo(0);
  }

  @Test
  public void getNextMediaItemIndex() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(1)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int nextMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getNextMediaItemIndex);

    assertThat(nextMediaItemIndex).isEqualTo(2);
  }

  @Test
  public void getNextMediaItemIndex_withRepeatModeOne() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(1)
            .setRepeatMode(Player.REPEAT_MODE_ONE)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int nextMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getNextMediaItemIndex);

    assertThat(nextMediaItemIndex).isEqualTo(2);
  }

  @Test
  public void getNextMediaItemIndex_atTheLastMediaItem() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(2)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int nextMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getNextMediaItemIndex);

    assertThat(nextMediaItemIndex).isEqualTo(C.INDEX_UNSET);
  }

  @Test
  public void getNextMediaItemIndex_atTheLastMediaItemWithRepeatModeAll() throws Exception {
    Timeline timeline = MediaTestUtils.createTimeline(/* windowCount= */ 3);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(2)
            .setRepeatMode(Player.REPEAT_MODE_ALL)
            .setShuffleModeEnabled(false)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int nextMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getNextMediaItemIndex);

    assertThat(nextMediaItemIndex).isEqualTo(0);
  }

  @Test
  public void getNextMediaItemIndex_withShuffleModeEnabled() throws Exception {
    Timeline timeline =
        new PlaylistTimeline(
            MediaTestUtils.createMediaItems(/* size= */ 3),
            /* shuffledIndices= */ new int[] {0, 2, 1});
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setTimeline(timeline)
            .setCurrentMediaItemIndex(2)
            .setRepeatMode(Player.REPEAT_MODE_OFF)
            .setShuffleModeEnabled(true)
            .build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int nextMediaItemIndex =
        threadTestRule.getHandler().postAndSync(controller::getNextMediaItemIndex);

    assertThat(nextMediaItemIndex).isEqualTo(1);
  }

  @Test
  public void getMediaItemCount() throws Exception {
    int windowCount = 3;
    Timeline timeline = MediaTestUtils.createTimeline(windowCount);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder().setTimeline(timeline).build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    int mediaItemCount = threadTestRule.getHandler().postAndSync(controller::getMediaItemCount);

    assertThat(mediaItemCount).isEqualTo(windowCount);
  }

  @Test
  public void getMediaItemAt() throws Exception {
    int windowCount = 3;
    int mediaItemIndex = 1;
    Timeline timeline = MediaTestUtils.createTimeline(windowCount);
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder().setTimeline(timeline).build();
    remoteSession.setPlayer(playerConfig);

    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    MediaItem mediaItem =
        threadTestRule.getHandler().postAndSync(() -> controller.getMediaItemAt(mediaItemIndex));

    assertThat(mediaItem)
        .isEqualTo(timeline.getWindow(mediaItemIndex, new Timeline.Window()).mediaItem);
  }

  private RemoteMediaSession createRemoteMediaSession(String id, Bundle tokenExtras)
      throws Exception {
    RemoteMediaSession session = new RemoteMediaSession(id, context, tokenExtras);
    remoteSessionList.add(session);
    return session;
  }

  @Test
  public void getCurrentPosition_whenNotPlaying_doesNotAdvance() throws Exception {
    long testCurrentPositionMs = 100L;
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setPlaybackState(Player.STATE_BUFFERING)
            .setCurrentPosition(testCurrentPositionMs)
            .setDuration(10_000L)
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    long currentPositionMs =
        threadTestRule
            .getHandler()
            .postAndSync(
                () -> {
                  controller.setTimeDiffMs(50L);
                  return controller.getCurrentPosition();
                });

    assertThat(currentPositionMs).isEqualTo(testCurrentPositionMs);
  }

  @Test
  public void getCurrentPosition_whenPlaying_advances() throws Exception {
    long testCurrentPosition = 100L;
    PlaybackParameters testPlaybackParameters = new PlaybackParameters(/* speed= */ 2.0f);
    long testTimeDiff = 50L;
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true)
            .setCurrentPosition(testCurrentPosition)
            .setDuration(10_000L)
            .setPlaybackParameters(testPlaybackParameters)
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    long currentPositionMs =
        threadTestRule
            .getHandler()
            .postAndSync(
                () -> {
                  controller.setTimeDiffMs(testTimeDiff);
                  return controller.getCurrentPosition();
                });

    long expectedCurrentPositionMs =
        testCurrentPosition + (long) (testTimeDiff * testPlaybackParameters.speed);
    assertThat(currentPositionMs).isEqualTo(expectedCurrentPositionMs);
  }

  @Test
  public void getContentPosition_whenPlayingAd_doesNotAdvance() throws Exception {
    long testContentPosition = 100L;
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setContentPosition(testContentPosition)
            .setDuration(10_000L)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true)
            .setIsPlayingAd(true)
            .setCurrentAdGroupIndex(0)
            .setCurrentAdIndexInAdGroup(0)
            .setPlaybackParameters(new PlaybackParameters(/* speed= */ 2.0f))
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    long contentPositionMs =
        threadTestRule
            .getHandler()
            .postAndSync(
                () -> {
                  controller.setTimeDiffMs(50L);
                  return controller.getContentPosition();
                });

    assertThat(contentPositionMs).isEqualTo(testContentPosition);
  }

  @Test
  public void getContentPosition_whenPlayingMainContent_returnsCurrentPosition() throws Exception {
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setCurrentPosition(100L)
            .setContentPosition(100L) // Same as current position b/c not playing an ad
            .setDuration(10_000L)
            .setPlayWhenReady(true)
            .setPlaybackState(Player.STATE_READY)
            .setIsPlayingAd(false)
            .setPlaybackParameters(new PlaybackParameters(/* speed= */ 2.0f))
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    long currentPositionMs =
        threadTestRule
            .getHandler()
            .postAndSync(
                () -> {
                  controller.setTimeDiffMs(50L);
                  return controller.getCurrentPosition();
                });
    long contentPositionMs =
        threadTestRule.getHandler().postAndSync(controller::getContentPosition);

    // expectedPositionMs = initCurrentPositionMs + deltaTime*playbackSpeed
    // 200L = 100L + 50L*2.0f
    assertThat(contentPositionMs).isEqualTo(200L);
    assertThat(currentPositionMs).isEqualTo(200L);
  }

  @Test
  public void getContentPosition_whenPlayingAd_returnsContentPosition() throws Exception {
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setCurrentPosition(10L)
            .setContentPosition(50L)
            .setDuration(10_000L)
            .setPlayWhenReady(true)
            .setPlaybackState(Player.STATE_READY)
            .setIsPlayingAd(true)
            .setCurrentAdGroupIndex(0)
            .setCurrentAdIndexInAdGroup(0)
            .setPlaybackParameters(new PlaybackParameters(/* speed= */ 2.0f))
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    long currentPositionMs =
        threadTestRule
            .getHandler()
            .postAndSync(
                () -> {
                  controller.setTimeDiffMs(50L);
                  return controller.getCurrentPosition();
                });
    long contentPositionMs =
        threadTestRule.getHandler().postAndSync(controller::getContentPosition);

    // expectedCurrentPositionMs = initCurrentPositionMs + deltaTime*playbackSpeed
    // 110L = 10L + 50L*2.0f
    assertThat(currentPositionMs).isEqualTo(110L);
    assertThat(contentPositionMs).isEqualTo(50L);
  }

  @Test
  public void getBufferedPosition_withPeriodicUpdate_updatedWithoutCallback() throws Exception {
    long testBufferedPosition = 999L;
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    remoteSession.getMockPlayer().setBufferedPosition(testBufferedPosition);

    remoteSession.setSessionPositionUpdateDelayMs(0L);

    PollingCheck.waitFor(
        TIMEOUT_MS,
        () -> {
          long bufferedPosition =
              threadTestRule.getHandler().postAndSync(controller::getBufferedPosition);
          return bufferedPosition == testBufferedPosition;
        });
  }

  @Test
  public void getContentBufferedPosition_byDefault_returnsZero() throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    long contentBufferedPositionMs =
        threadTestRule.getHandler().postAndSync(controller::getContentBufferedPosition);
    assertThat(contentBufferedPositionMs).isEqualTo(0);
  }

  @Test
  public void isCommandAvailable_withAvailableCommand_returnsTrue() throws Exception {
    @Player.Command int command = Player.COMMAND_PLAY_PAUSE;
    remoteSession.getMockPlayer().notifyAvailableCommandsChanged(createPlayerCommandsWith(command));
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(
            threadTestRule.getHandler().postAndSync(() -> controller.isCommandAvailable(command)))
        .isTrue();
  }

  @Test
  public void isCommandAvailable_withUnavailableCommand_returnsFalse() throws Exception {
    @Player.Command int command = Player.COMMAND_PLAY_PAUSE;
    remoteSession
        .getMockPlayer()
        .notifyAvailableCommandsChanged(createPlayerCommandsWithout(command));
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(
            threadTestRule.getHandler().postAndSync(() -> controller.isCommandAvailable(command)))
        .isFalse();
  }

  @Test
  public void isSessionCommandAvailable_withAvailablePredefinedSessionCommand_returnsTrue()
      throws Exception {
    @SessionCommand.CommandCode
    int sessionCommandCode = SessionCommand.COMMAND_CODE_SESSION_SET_RATING;
    SessionCommand sessionCommand = new SessionCommand(sessionCommandCode);
    Bundle tokenExtras = new Bundle();
    tokenExtras.putBundle(
        KEY_AVAILABLE_SESSION_COMMANDS,
        new SessionCommands.Builder().add(sessionCommand).build().toBundle());
    RemoteMediaSession remoteSession =
        createRemoteMediaSession(TEST_IS_SESSION_COMMAND_AVAILABLE, tokenExtras);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(
            threadTestRule
                .getHandler()
                .postAndSync(() -> controller.isSessionCommandAvailable(sessionCommandCode)))
        .isTrue();
    assertThat(
            threadTestRule
                .getHandler()
                .postAndSync(() -> controller.isSessionCommandAvailable(sessionCommand)))
        .isTrue();
  }

  @Test
  public void isSessionCommandAvailable_withUnavailablePredefinedSessionCommand_returnsFalse()
      throws Exception {
    @SessionCommand.CommandCode
    int sessionCommandCode = SessionCommand.COMMAND_CODE_SESSION_SET_RATING;
    SessionCommand sessionCommand = new SessionCommand(sessionCommandCode);
    Bundle tokenExtras = new Bundle();
    tokenExtras.putBundle(
        KEY_AVAILABLE_SESSION_COMMANDS,
        new SessionCommands.Builder()
            .addAllPredefinedCommands()
            .remove(sessionCommand)
            .build()
            .toBundle());
    RemoteMediaSession remoteSession =
        createRemoteMediaSession(TEST_IS_SESSION_COMMAND_AVAILABLE, tokenExtras);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(
            threadTestRule
                .getHandler()
                .postAndSync(() -> controller.isSessionCommandAvailable(sessionCommandCode)))
        .isFalse();
    assertThat(
            threadTestRule
                .getHandler()
                .postAndSync(() -> controller.isSessionCommandAvailable(sessionCommand)))
        .isFalse();
  }

  @Test
  public void isSessionCommandAvailable_withAvailableCustomSessionCommand_returnsTrue()
      throws Exception {
    SessionCommand sessionCommand = new SessionCommand("action", /* extras= */ Bundle.EMPTY);
    Bundle tokenExtras = new Bundle();
    tokenExtras.putBundle(
        KEY_AVAILABLE_SESSION_COMMANDS,
        new SessionCommands.Builder().add(sessionCommand).build().toBundle());
    RemoteMediaSession remoteSession =
        createRemoteMediaSession(TEST_IS_SESSION_COMMAND_AVAILABLE, tokenExtras);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(
            threadTestRule
                .getHandler()
                .postAndSync(() -> controller.isSessionCommandAvailable(sessionCommand)))
        .isTrue();
  }

  @Test
  public void isSessionCommandAvailable_withUnavailableCustomSessionCommand_returnsFalse()
      throws Exception {
    SessionCommand sessionCommand = new SessionCommand("action", /* extras= */ Bundle.EMPTY);
    Bundle tokenExtras = new Bundle();
    tokenExtras.putBundle(
        KEY_AVAILABLE_SESSION_COMMANDS,
        new SessionCommands.Builder().addAllPredefinedCommands().build().toBundle());
    RemoteMediaSession remoteSession =
        createRemoteMediaSession(TEST_IS_SESSION_COMMAND_AVAILABLE, tokenExtras);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    assertThat(
            threadTestRule
                .getHandler()
                .postAndSync(() -> controller.isSessionCommandAvailable(sessionCommand)))
        .isFalse();
  }

  @Test
  public void getMediaMetadata() throws Exception {
    MediaMetadata testMediaMetadata =
        new MediaMetadata.Builder()
            .setArtist("artist")
            .setArtworkData(new byte[] {1, 2, 3, 4})
            .setSubtitle("subtitle")
            .setTitle("title")
            .setUserRating(new StarRating(/* maxStars= */ 5, /* starRating= */ 1))
            .build();
    Bundle playerConfig =
        new RemoteMediaSession.MockPlayerConfigBuilder()
            .setMediaMetadata(testMediaMetadata)
            .build();
    remoteSession.setPlayer(playerConfig);
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());

    MediaMetadata mediaMetadata =
        threadTestRule.getHandler().postAndSync(controller::getMediaMetadata);

    assertThat(mediaMetadata).isEqualTo(testMediaMetadata);
  }

  @Test
  public void
      setMediaItems_setLessMediaItemsThanCurrentMediaItemIndex_masksCurrentMediaItemIndexAndStateCorrectly()
          throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    List<MediaItem> threeItemsList =
        ImmutableList.of(
            MediaItem.fromUri("http://www.google.com/1"),
            MediaItem.fromUri("http://www.google.com/2"),
            MediaItem.fromUri("http://www.google.com/3"));
    List<MediaItem> twoItemsList =
        ImmutableList.of(
            MediaItem.fromUri("http://www.google.com/1"),
            MediaItem.fromUri("http://www.google.com/2"));

    int[] currentMediaIndexAndState =
        threadTestRule
            .getHandler()
            .postAndSync(
                () -> {
                  controller.setMediaItems(threeItemsList);
                  controller.prepare();
                  controller.seekTo(/* mediaItemIndex= */ 2, /* positionMs= */ C.TIME_UNSET);
                  controller.setMediaItems(twoItemsList);
                  return new int[] {
                    controller.getCurrentMediaItemIndex(), controller.getPlaybackState()
                  };
                });

    assertThat(currentMediaIndexAndState[0]).isEqualTo(0);
    assertThat(currentMediaIndexAndState[1]).isEqualTo(Player.STATE_BUFFERING);
  }

  @Test
  public void
      setMediaItems_setLessMediaItemsThanCurrentMediaItemIndexResetPositionFalse_masksCurrentMediaItemIndexAndStateCorrectly()
          throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    List<MediaItem> threeItemsList =
        ImmutableList.of(
            MediaItem.fromUri("http://www.google.com/1"),
            MediaItem.fromUri("http://www.google.com/2"),
            MediaItem.fromUri("http://www.google.com/3"));
    List<MediaItem> twoItemsList =
        ImmutableList.of(
            MediaItem.fromUri("http://www.google.com/1"),
            MediaItem.fromUri("http://www.google.com/2"));

    int[] currentMediaItemIndexAndState =
        threadTestRule
            .getHandler()
            .postAndSync(
                () -> {
                  controller.setMediaItems(threeItemsList);
                  controller.prepare();
                  controller.seekTo(/* mediaItemIndex= */ 2, /* positionMs= */ C.TIME_UNSET);
                  controller.setMediaItems(twoItemsList, /* resetPosition= */ false);
                  return new int[] {
                    controller.getCurrentMediaItemIndex(), controller.getPlaybackState()
                  };
                });

    assertThat(currentMediaItemIndexAndState[0]).isEqualTo(0);
    assertThat(currentMediaItemIndexAndState[1]).isEqualTo(Player.STATE_ENDED);
  }

  @Test
  public void setMediaItems_startIndexTooLarge_throwIllegalSeekPositionException()
      throws Exception {
    MediaController controller = controllerTestRule.createController(remoteSession.getToken());
    List<MediaItem> threeItemsList =
        ImmutableList.of(
            MediaItem.fromUri("http://www.google.com/1"),
            MediaItem.fromUri("http://www.google.com/2"),
            MediaItem.fromUri("http://www.google.com/3"));

    Assert.assertThrows(
        IllegalSeekPositionException.class,
        () ->
            threadTestRule
                .getHandler()
                .postAndSync(
                    () -> {
                      controller.setMediaItems(
                          threeItemsList,
                          /* startIndex= */ 99,
                          /* startPositionMs= */ C.TIME_UNSET);
                      return controller.getCurrentMediaItemIndex();
                    }));
  }
}
