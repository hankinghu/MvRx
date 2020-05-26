@file:Suppress("UtilityClassWithPublicConstructor")

package com.airbnb.mvrx.mocking


import android.os.Bundle
import android.os.Parcelable
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.airbnb.mvrx.MvRx
import io.reactivex.Scheduler
import io.reactivex.annotations.NonNull
import io.reactivex.disposables.Disposable
import io.reactivex.exceptions.CompositeException
import io.reactivex.internal.schedulers.ExecutorScheduler
import io.reactivex.plugins.RxJavaPlugins
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Ignore
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Ignore
abstract class BaseTest {

    @Before
    @After
    fun resetConfigurationDefaults() {
        // Use a null context since we don't need mock printing during tests
        MavericksMocks.install(debugMode = true, mocksEnabled = true, context = null)
        MavericksMocks.mockConfigFactory.mockBehavior = MockBehavior(
            stateStoreBehavior = MockBehavior.StateStoreBehavior.Synchronous
        )
    }

    protected inline fun <reified F : Fragment, reified A : AppCompatActivity> createFragment(
        savedInstanceState: Bundle? = null,
        args: Parcelable? = null,
        containerId: Int? = null,
        existingController: ActivityController<A>? = null
    ): Pair<ActivityController<A>, F> {
        val controller = existingController ?: Robolectric.buildActivity(A::class.java)

        if (existingController == null) {
            if (savedInstanceState == null) {
                controller.setup()
            } else {
                controller.setup(savedInstanceState)
            }
        }

        val activity = controller.get()
        val fragment = if (savedInstanceState == null) {
            F::class.java.newInstance().apply {
                arguments = Bundle().apply { putParcelable(MvRx.KEY_ARG, args) }
                activity.supportFragmentManager
                    .beginTransaction()
                    .also {
                        if (containerId != null) {
                            it.add(containerId, this, "TAG")
                        } else {
                            it.add(this, "TAG")
                        }
                    }
                    .commitNow()
            }
        } else {
            activity.supportFragmentManager.findFragmentByTag("TAG") as F
        }
        return controller to fragment
    }

    protected inline fun <F : Fragment, reified A : AppCompatActivity> F.addToActivity(
        containerId: Int? = null,
        existingController: ActivityController<A>? = null
    ): ActivityController<A> {
        val controller = existingController ?: Robolectric.buildActivity(A::class.java)

        if (existingController == null) {
            controller.setup()
        }

        val activity = controller.get()

        activity.supportFragmentManager
            .beginTransaction()
            .also {
                if (containerId != null) {
                    it.add(containerId, this, "TAG")
                } else {
                    it.add(this, "TAG")
                }
            }
            .commitNow()


        return controller
    }

    protected inline fun <reified F : Fragment> ActivityController<out AppCompatActivity>.mvRxFragment(): F {
        return get().supportFragmentManager.findFragmentByTag("TAG") as F
    }
}
