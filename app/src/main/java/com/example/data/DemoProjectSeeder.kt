package com.example.data

import com.example.model.AspectRatioOption
import com.example.model.CaptionChunk
import com.example.model.CaptionStylePreset
import com.example.model.DEMO_VIDEO_URI
import com.example.model.ProjectStatus
import com.example.model.TargetDuration
import com.example.model.VideoProject
import com.example.model.ViralClip

object DemoProjectSeeder {

    fun createDemoProject(): VideoProject = VideoProject(
        title = "Creator Growth Podcast Ep. 42",
        sourceUri = DEMO_VIDEO_URI,
        thumbnailPath = null,
        durationMs = 245_000L, // 04:05
        sourceWidth = 1920,
        sourceHeight = 1080,
        targetDuration = TargetDuration.BETWEEN_30_60S,
        aspectRatio = AspectRatioOption.VERTICAL_9_16,
        defaultCaptionStyle = CaptionStylePreset.HORMOZI_BOLD,
        focusKeywords = "retention hooks, pricing, storytelling",
        status = ProjectStatus.READY,
        clipCount = 4,
        isDemo = true
    )

    fun createDemoClips(projectId: Long): List<ViralClip> = listOf(
        ViralClip(
            projectId = projectId,
            title = "Why 90% of Shorts Die in the First 2 Seconds",
            startMs = 12_000L,
            endMs = 40_000L,
            viralityScore = 94,
            hookScore = 97,
            flowScore = 92,
            engagementScore = 93,
            reason = "Opens with a contrarian statistic that creates an instant curiosity gap before viewers can swipe away. Delivers a tight three-part fix with a punchy takeaway that drives saves and comments.",
            hashtags = listOf("#ShortFormHooks", "#CreatorTips", "#ViralEditing", "#ReelsStrategy"),
            captions = listOf(
                CaptionChunk(0L, 2_400L, "Ninety percent of shorts", "Ninety"),
                CaptionChunk(2_400L, 5_000L, "die in two seconds.", "die"),
                CaptionChunk(5_000L, 7_800L, "Not because your topic", "topic"),
                CaptionChunk(7_800L, 10_600L, "is boring or weak.", "boring"),
                CaptionChunk(10_600L, 13_400L, "You waste frame one", "waste"),
                CaptionChunk(13_400L, 16_200L, "saying Hey guys welcome!", "Hey"),
                CaptionChunk(16_200L, 19_200L, "Cut the throat clearing.", "Cut"),
                CaptionChunk(19_200L, 22_400L, "Start right on the", "Start"),
                CaptionChunk(22_400L, 25_200L, "shocking result instead.", "shocking"),
                CaptionChunk(25_200L, 28_000L, "Watch retention double overnight.", "double")
            ),
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            autoFaceTrackEnabled = true,
            faceCenterX = -0.14f,
            manualPanX = -0.14f,
            zoomScale = 1.08f,
            captionStyle = CaptionStylePreset.HORMOZI_BOLD,
            captionYPosition = 0.74f,
            captionUppercase = true
        ),
        ViralClip(
            projectId = projectId,
            title = "The $10K Pricing Mistake Freelancers Repeat",
            startMs = 68_500L,
            endMs = 95_500L,
            viralityScore = 87,
            hookScore = 89,
            flowScore = 86,
            engagementScore = 85,
            reason = "Anchors immediately on a concrete dollar figure that stops freelance creators mid-scroll. The shift from hourly billing to outcome pricing offers a shareable mental model with strong comment debate potential.",
            hashtags = listOf("#PricingTips", "#FreelanceGrowth", "#CreativeBusiness", "#ValuePricing"),
            captions = listOf(
                CaptionChunk(0L, 2_700L, "Stop charging by the hour.", "Stop"),
                CaptionChunk(2_700L, 5_400L, "Every time you get faster,", "faster,"),
                CaptionChunk(5_400L, 8_200L, "you literally give yourself", "literally"),
                CaptionChunk(8_200L, 10_800L, "a massive pay cut.", "pay"),
                CaptionChunk(10_800L, 13_800L, "Clients never buy your", "never"),
                CaptionChunk(13_800L, 16_600L, "editing hours on screen.", "hours"),
                CaptionChunk(16_600L, 19_600L, "They buy the outcome", "outcome"),
                CaptionChunk(19_600L, 22_800L, "and the views it drives.", "views"),
                CaptionChunk(22_800L, 27_000L, "Price the transformation instead.", "transformation")
            ),
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            autoFaceTrackEnabled = true,
            faceCenterX = 0.16f,
            manualPanX = 0.16f,
            zoomScale = 1.05f,
            captionStyle = CaptionStylePreset.NEON_POP,
            captionYPosition = 0.72f,
            captionUppercase = true
        ),
        ViralClip(
            projectId = projectId,
            title = "How One Story Beat Turns Casual Viewers Into Fans",
            startMs = 132_000L,
            endMs = 156_000L,
            viralityScore = 78,
            hookScore = 76,
            flowScore = 82,
            engagementScore = 77,
            reason = "Strong pacing and clear storytelling structure keep viewers engaged through the middle arc. The opening hook is slightly softer than top-tier clips, making it ideal for warm audience nurturing.",
            hashtags = listOf("#Storytelling", "#ContentCreation", "#AudienceBuilding"),
            captions = listOf(
                CaptionChunk(0L, 2_800L, "Facts inform your audience,", "Facts"),
                CaptionChunk(2_800L, 5_800L, "but tension keeps them glued.", "tension"),
                CaptionChunk(5_800L, 9_000L, "Always introduce one obstacle", "obstacle"),
                CaptionChunk(9_000L, 12_200L, "before revealing the answer.", "before"),
                CaptionChunk(12_200L, 15_400L, "That tiny three-second delay", "delay"),
                CaptionChunk(15_400L, 18_800L, "triggers dopamine loops.", "dopamine"),
                CaptionChunk(18_800L, 24_000L, "And turns viewers into fans.", "fans.")
            ),
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            autoFaceTrackEnabled = true,
            faceCenterX = 0.04f,
            manualPanX = 0.04f,
            zoomScale = 1.0f,
            captionStyle = CaptionStylePreset.MINIMAL_CLEAN,
            captionYPosition = 0.76f,
            captionUppercase = false
        ),
        ViralClip(
            projectId = projectId,
            title = "My Simple 3-App Mobile Desk Lighting Setup",
            startMs = 188_000L,
            endMs = 208_000L,
            viralityScore = 66,
            hookScore = 63,
            flowScore = 71,
            engagementScore = 64,
            reason = "Practical gear breakdown with clean visual pacing and zero filler pauses. Narrower niche appeal caps broad algorithmic reach, though gear enthusiasts still bookmark it.",
            hashtags = listOf("#DeskSetup", "#MobileCreator", "#StudioLighting"),
            captions = listOf(
                CaptionChunk(0L, 3_000L, "You do not need", "not"),
                CaptionChunk(3_000L, 6_000L, "a thousand dollar camera.", "thousand"),
                CaptionChunk(6_000L, 9_500L, "Put one soft key light", "soft"),
                CaptionChunk(9_500L, 13_000L, "at forty five degrees.", "forty"),
                CaptionChunk(13_000L, 16_500L, "Drop your background exposure", "Drop"),
                CaptionChunk(16_500L, 20_000L, "and your phone looks cinematic.", "cinematic.")
            ),
            aspectRatio = AspectRatioOption.VERTICAL_9_16,
            autoFaceTrackEnabled = false,
            faceCenterX = 0f,
            manualPanX = 0f,
            zoomScale = 1.0f,
            captionStyle = CaptionStylePreset.HORMOZI_BOLD,
            captionYPosition = 0.75f,
            captionUppercase = true
        )
    )
}
