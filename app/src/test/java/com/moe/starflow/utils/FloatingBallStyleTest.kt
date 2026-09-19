package com.moe.starflow.utils

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import androidx.preference.PreferenceManager
import com.moe.starflow.R
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * 悬浮球「大小 / 透明度」守卫（游戏与漫画**共用**同一份设置）。
 *
 * 真机上最容易出的两类问题，这里各钉一条：
 * 1. **越界百分比直接落进 layoutParams** → 球被撑爆/缩没（读的时候必须夹到 [MIN, MAX]）；
 * 2. **大小走 `scaleX/scaleY`** → 点击脉冲、双击、长按反馈动画结束都会把 scale 收回 1f，
 *    用户设的大小"点一下球就没了"。所以尺寸必须落在子视图 layoutParams 上，透明度落在根视图 alpha 上。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingBallStyleTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun prefs() = PreferenceManager.getDefaultSharedPreferences(ctx)

    @Before
    fun clearPrefs() {
        prefs().edit().clear().commit()
    }

    @Test
    fun unsetPrefs_fallBackTo100Percent() {
        assertEquals(FloatingBallStyle.DEFAULT_SIZE_PERCENT, FloatingBallStyle.sizePercent(prefs()))
        assertEquals(FloatingBallStyle.DEFAULT_ALPHA_PERCENT, FloatingBallStyle.alphaPercent(prefs()))
        assertEquals(1f, FloatingBallStyle.alpha(prefs()), 0.0001f)
    }

    @Test
    fun outOfRangeValues_areClampedOnRead() {
        prefs().edit()
            .putInt(FloatingBallStyle.KEY_SIZE, 9999)
            .putInt(FloatingBallStyle.KEY_ALPHA, -5)
            .commit()
        assertEquals(FloatingBallStyle.MAX_SIZE_PERCENT, FloatingBallStyle.sizePercent(prefs()))
        assertEquals(FloatingBallStyle.MIN_ALPHA_PERCENT, FloatingBallStyle.alphaPercent(prefs()))
    }

    /**
     * 基准必须等于**布局里真实的默认边长**：`BASE_SIZE_DP` 是 `floatball_layout.xml` 的手抄副本，
     * 只用 `sizePx(ctx, N)` 当期望值的话断言会自证（布局改成 80dp 也照样绿，"100%"却悄悄变成 81%）。
     * 这里直接读 inflate 出来的子视图边长当基准。
     */
    @Test
    fun baseSize_matchesLayoutDefault() {
        val view = LayoutInflater.from(ctx).inflate(R.layout.floatball_layout, null)
        val layoutSize = view.findViewById<View>(R.id.floating_ball_icon).layoutParams.width
        assertEquals(
            "BASE_SIZE_DP 与 floatball_layout.xml 的默认边长不一致（百分比语义会漂）",
            layoutSize,
            FloatingBallStyle.sizePx(ctx, 100)
        )
    }

    /** 基准是布局里的 65dp：百分比必须按 dp → px 换算（直接写百分比会得到 100px 这种荒唐尺寸）。 */
    @Test
    fun sizePx_scalesFromBaseDp() {
        val density = ctx.resources.displayMetrics.density
        assertEquals(
            (FloatingBallStyle.BASE_SIZE_DP * density).roundToInt(),
            FloatingBallStyle.sizePx(ctx, 100)
        )
        assertEquals(
            (FloatingBallStyle.BASE_SIZE_DP * 2f * density).roundToInt(),
            FloatingBallStyle.sizePx(ctx, 200)
        )
        assertEquals(
            (FloatingBallStyle.BASE_SIZE_DP / 2f * density).roundToInt(),
            FloatingBallStyle.sizePx(ctx, 50)
        )
    }

    @Test
    fun alpha_isPercentToFactor() {
        assertEquals(0.1f, FloatingBallStyle.alpha(10), 0.0001f)
        assertEquals(0.5f, FloatingBallStyle.alpha(50), 0.0001f)
        assertEquals(1f, FloatingBallStyle.alpha(100), 0.0001f)
    }

    /**
     * 落到真实布局上：两个子视图（图标 + 错误圈）一起改尺寸，根视图拿透明度，**且不动 scale**。
     */
    @Test
    fun apply_setsChildLayoutParamsAndRootAlpha_only() {
        val view = LayoutInflater.from(ctx).inflate(R.layout.floatball_layout, null)
        val icon = view.findViewById<View>(R.id.floating_ball_icon)
        val ring = view.findViewById<View>(R.id.floating_ball_error_ring)
        prefs().edit()
            .putInt(FloatingBallStyle.KEY_SIZE, 150)
            .putInt(FloatingBallStyle.KEY_ALPHA, 40)
            .commit()

        FloatingBallStyle.apply(view, prefs())

        val expected = FloatingBallStyle.sizePx(ctx, 150)
        assertEquals("图标宽", expected, icon.layoutParams.width)
        assertEquals("图标高", expected, icon.layoutParams.height)
        assertEquals("错误圈宽（与图标同步）", expected, ring.layoutParams.width)
        assertEquals("错误圈高（与图标同步）", expected, ring.layoutParams.height)
        assertEquals("大小绝不能走 scale（会被反馈动画收尾抹掉）", 1f, icon.scaleX, 0.0001f)
        assertEquals(1f, icon.scaleY, 0.0001f)
        assertEquals("透明度落在根视图（图标 + 错误圈一起淡出）", 0.4f, view.alpha, 0.0001f)
    }

    /** 再调一次（用户连续拖滑块）必须幂等：同样的值不重建 layoutParams。 */
    @Test
    fun apply_isIdempotentForSameValues() {
        val view = LayoutInflater.from(ctx).inflate(R.layout.floatball_layout, null)
        val icon = view.findViewById<View>(R.id.floating_ball_icon)
        prefs().edit().putInt(FloatingBallStyle.KEY_SIZE, 80).putInt(FloatingBallStyle.KEY_ALPHA, 70).commit()

        FloatingBallStyle.apply(view, prefs())
        val first = icon.layoutParams
        FloatingBallStyle.apply(view, prefs())

        assertEquals("相同取值应复用同一份 layoutParams 对象", first, icon.layoutParams)
        assertEquals(0.7f, view.alpha, 0.0001f)
    }
}
