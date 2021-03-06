package anonymouls.dev.mgcex.app.main.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.*
import androidx.lifecycle.Observer
import anonymouls.dev.mgcex.app.AlarmActivity
import anonymouls.dev.mgcex.app.R
import anonymouls.dev.mgcex.app.backend.Algorithm
import anonymouls.dev.mgcex.app.backend.CommandCallbacks
import anonymouls.dev.mgcex.app.backend.CommandInterpreter
import anonymouls.dev.mgcex.app.backend.ServiceRessurecter
import anonymouls.dev.mgcex.app.data.DataFragment
import anonymouls.dev.mgcex.app.main.MultitaskFragment
import anonymouls.dev.mgcex.app.main.SettingsFragment
import anonymouls.dev.mgcex.databaseProvider.HRRecord
import anonymouls.dev.mgcex.util.PreferenceListener
import anonymouls.dev.mgcex.util.Utils
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.Libs.SpecialButton
import com.mikepenz.aboutlibraries.LibsBuilder
import com.mikepenz.aboutlibraries.LibsConfiguration.LibsListener
import com.mikepenz.aboutlibraries.LibsConfiguration.LibsListenerImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.*
import kotlin.system.exitProcess


@ExperimentalStdlibApi
class MyViewModelFactory(private val activity: FragmentActivity) : ViewModelProvider.Factory {
    override fun <T : ViewModel?> create(modelClass: Class<T>): T {
        return MainViewModel(activity) as T
    }

}

@ExperimentalUnsignedTypes
@ExperimentalStdlibApi
class MainViewModel(private val activity: FragmentActivity) : ViewModel(), CommandInterpreter.CommandReaction {
    private val workInProgress = MutableLiveData(View.GONE)
    private val mCurrentStatus = MutableLiveData<String>(activity.getString(R.string.status_label))
    private val mHRVisibility = MutableLiveData<Int>(View.GONE)

    val mbatteryHolder = MutableLiveData(-1)
    val mLastHearthRateIncomed = MutableLiveData<HRRecord>(HRRecord(Calendar.getInstance(), -1))
    val mLastCcalsIncomed = MutableLiveData<Int>(-1)
    val mLastStepsIncomed = MutableLiveData(-1)

    //region Live Data

    private val currentSteps: LiveData<Int>
        get() {
            return mLastStepsIncomed
        }
    val currentHR: LiveData<HRRecord>
        get() {
            return mLastHearthRateIncomed
        }
    private val currentBattery: LiveData<Int>
        get() {
            return mbatteryHolder
        }
    private val currentCcals: LiveData<Int>
        get() {
            return mLastCcalsIncomed
        }
    val currentStatus: LiveData<String>
        get() {
            return mCurrentStatus
        }
    val progressVisibility: LiveData<Int>
        get() {
            return workInProgress
        }
    val hrVisibility: LiveData<Int>
        get() {
            return mHRVisibility
        }
    //endregion

    private var firstLaunch = true

    init {
        publicModel = this
    }

    //region Observers

    private fun createStatusObserver(owner: LifecycleOwner) {
        Algorithm.StatusCode.removeObservers(owner)
        Algorithm.currentAlgoStatus.removeObservers(owner)

        Algorithm.StatusCode.observe(owner, Observer {
            if (it.code < Algorithm.StatusCodes.GattReady.code) {
                mHRVisibility.postValue(View.GONE)
                workInProgress.postValue(View.VISIBLE)
            } else {
                workInProgress.postValue(View.GONE)
                if (CommandInterpreter.getInterpreter(activity).hRRealTimeControlSupport)
                    mHRVisibility.postValue(View.VISIBLE)
                else
                    mHRVisibility.postValue(View.GONE)
                if (firstLaunch) firstLaunch = false
            }
        })

        Algorithm.currentAlgoStatus.observe(owner, Observer {
            mCurrentStatus.postValue(it)
        })
    }

    private fun createBatteryObserver(owner: LifecycleOwner) {
        if (currentBattery.hasActiveObservers()) return
        currentBattery.observe(owner, Observer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (it > 5) {
                    activity.findViewById<TextView>(R.id.BatteryStatus)?.visibility = View.VISIBLE
                    activity.findViewById<TextView>(R.id.BatteryStatus)?.text = it.toString()
                } else
                    activity.findViewById<TextView>(R.id.BatteryStatus)?.visibility = View.INVISIBLE
                when {
                    it < 5 -> {}
                    it < 33 ->
                        activity.findViewById<ImageView>(R.id.batteryIcon)?.setImageDrawable(activity.getDrawable(R.drawable.chargelow_icon))
                    it < 66 ->
                        activity.findViewById<ImageView>(R.id.batteryIcon)?.setImageDrawable(activity.getDrawable(R.drawable.chargemed_icon))
                    else -> activity.findViewById<ImageView>(R.id.batteryIcon)?.setImageDrawable(activity.getDrawable(R.drawable.chargefull_icon))
                }
            } else {
                when {
                    it < 5 -> {}
                    it < 33 ->
                        activity.findViewById<ImageView>(R.id.batteryIcon)
                                ?.setImageResource(R.drawable.chargelow_icon)
                    it < 66 ->
                        activity.findViewById<ImageView>(R.id.batteryIcon)
                                ?.setImageResource((R.drawable.chargemed_icon))
                    else -> activity.findViewById<ImageView>(R.id.batteryIcon)
                            ?.setImageResource((R.drawable.chargefull_icon))
                }

            }
        })
    }

    private fun <T> createTextObserverUniversal(id: Int, dataToObserve: LiveData<T>, owner: LifecycleOwner) {
        if (dataToObserve.hasActiveObservers()) return
        dataToObserve.observe(owner, Observer {
            val string: String = when (it) {
                is HRRecord -> it.hr.toString()
                is Int -> it.toString()
                else -> it as String
            }
            if (Integer.parseInt(string) > 0) {
                activity.findViewById<TextView>(id)?.visibility = View.VISIBLE
                activity.findViewById<TextView>(id)?.text = string
            } else
                activity.findViewById<TextView>(id)?.visibility = View.INVISIBLE
        })
    }

    private fun createValuesObserver(owner: LifecycleOwner) {
        createTextObserverUniversal(R.id.HRValue, currentHR, owner)
        createTextObserverUniversal(R.id.StepsValue, currentSteps, owner)
        createTextObserverUniversal(R.id.CaloriesValue, currentCcals, owner)
    }

    //endregion

    private fun demoMode(): Boolean {
        return if (!Utils.getSharedPrefs(activity).contains(PreferenceListener.Companion.PrefsConsts.bandAddress)) {
            mCurrentStatus.postValue(activity.getString(R.string.demo_mode))
            activity.runOnUiThread { mCurrentStatus.value = activity.getString(R.string.demo_mode) }
            mHRVisibility.postValue(View.GONE)
            workInProgress.postValue(View.GONE)
            true
        } else
            false
    }

    private fun launchDataGraph(Data: DataFragment.DataTypes) {
        val transaction = activity.supportFragmentManager.beginTransaction()
        transaction.addToBackStack(null)
        transaction.replace(R.id.container, DataFragment.newInstance(Data))
        transaction.commit()
    }

    private fun launchActivity(newIntent: Intent) {
        newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(newIntent)
    }

    private fun changeFragment(frag: Fragment) {
        val transaction = activity.supportFragmentManager.beginTransaction()
        transaction.replace(R.id.container, frag)
        transaction.addToBackStack(null)
        transaction.commit()
    }

    fun onClickHandler(view: View) {
        val ID = view.id
        when (ID) {
            R.id.realtimeHRSync -> {
                Algorithm.SelfPointer?.ci?.hRRealTimeControl((view as Switch).isChecked)
            }
            R.id.ExitBtnContainer -> {
                Algorithm.IsActive = false
                Algorithm.SelfPointer?.killService()
                activity.stopService(Intent(activity, Algorithm::class.java))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    activity.finishAndRemoveTask()
                } else activity.finish()
                val stopIntent = Intent(activity, Algorithm::class.java)
                stopIntent.action = "ACTION_STOP"
                activity.startService(stopIntent)
                ServiceRessurecter.cancelJob(activity)
                exitProcess(0)
            }
            R.id.SyncNowContainer -> {
                if (this.workInProgress.value!! != View.GONE) {
                    Toast.makeText(activity, activity.getString(R.string.wait_untill_complete), Toast.LENGTH_LONG).show()
                } else {
                    Algorithm.SelfPointer?.executeForceSync()
                }
            }
            R.id.HRContainer -> launchDataGraph(DataFragment.DataTypes.HR)
            R.id.StepsContainer -> launchDataGraph(DataFragment.DataTypes.Steps)
            R.id.CaloriesContainer -> launchDataGraph(DataFragment.DataTypes.Calories)
            R.id.SettingContainer -> changeFragment(SettingsFragment())
            R.id.ReportContainer -> changeFragment(MultitaskFragment())
            R.id.AlarmContainer -> if (Algorithm.IsAlarmingTriggered) {
                Algorithm.IsAlarmingTriggered = false
                Algorithm.IsAlarmWaiting = false
                Algorithm.IsAlarmKilled = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    view.background = activity.getDrawable(R.drawable.custom_border)
                } else {
                    view.background = ContextCompat.getDrawable(activity, R.drawable.custom_border)
                }
                Algorithm.SelfPointer?.ci?.stopLongAlarm()
            } else {
                launchActivity(Intent(activity, AlarmActivity::class.java))
            }
            R.id.InfoContainer ->{
                val fragment = LibsBuilder()
                        .withFields(R.string::class.java.fields)
                        .withLibraryModification("aboutlibraries", Libs.LibraryFields.LIBRARY_NAME, "_AboutLibraries") // optionally apply modifications for library information
                        .withAboutIconShown(true)
                        //.withAboutVersionShownCode(true)
                        .withVersionShown(true)
                        .withLicenseShown(true)
                        //.withAboutAppName(activity.getString(R.string.app_name))
                        .withAboutDescription(activity.getString(R.string.about_description))
                        .withListener(aboutButtonListener)
                        .withAboutSpecial1(activity.getString(R.string.privacy_policy))
                        .supportFragment()
                changeFragment(fragment)
            } // TODO About dialog
            // TODO one day R.id.SleepContainer ->
            R.id.BatteryContainer -> Toast.makeText(activity, activity.getString(R.string.battery_health_not_ready), Toast.LENGTH_LONG).show()
        }
    }

    private val aboutButtonListener: LibsListener = object : LibsListenerImpl() {
        override fun onIconClicked(v: View) {
            // ignore
        }

        override fun onExtraClicked(view: View, specialButton: SpecialButton): Boolean {
            return when (specialButton) {
                SpecialButton.SPECIAL1 -> {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gist.github.com/anonymouls/82b8749e96e69370963f8ba61152068e"))
                    activity.startActivity(browserIntent)
                    true
                }
                else -> super.onExtraClicked(view, specialButton)
            }
        }
    }

    fun removeObservers(owner: LifecycleOwner){
        currentStatus.removeObservers(owner)
        currentHR.removeObservers(owner)
        currentBattery.removeObservers(owner)
        currentCcals.removeObservers(owner)
        currentSteps.removeObservers(owner)
        hrVisibility.removeObservers(owner)
        workInProgress.removeObservers(owner)
    }
    fun reInit(owner: LifecycleOwner) {
        if (demoMode()) return
        activity.runOnUiThread {
            createValuesObserver(owner)
            createBatteryObserver(owner)
            createStatusObserver(owner)
        }

        if (Algorithm.StatusCode.value!!.code >= Algorithm.StatusCodes.GattReady.code
                && CommandInterpreter.getInterpreter(activity).hRRealTimeControlSupport)
            mHRVisibility.postValue(View.VISIBLE)
        else
            mHRVisibility.postValue(View.GONE)
        restore()
    }
    fun restore(){
        GlobalScope.launch(Dispatchers.Default) {
            if (CommandCallbacks.getCallback(activity).savedCCals != 0)
                mLastCcalsIncomed.postValue(CommandCallbacks.getCallback(activity).savedCCals)
            if (CommandCallbacks.getCallback(activity).savedSteps != 0)
                mLastStepsIncomed.postValue(CommandCallbacks.getCallback(activity).savedSteps)
            if (CommandCallbacks.getCallback(activity).savedBattery != 0)
                mbatteryHolder.postValue(CommandCallbacks.getCallback(activity).savedBattery)
            if (CommandCallbacks.getCallback(activity).savedHR.hr > -1)
                mLastHearthRateIncomed.postValue(CommandCallbacks.getCallback(activity).savedHR)
            if (Algorithm.SelfPointer != null)
                mCurrentStatus.postValue(Algorithm.currentAlgoStatus.value!!)
        }
    }

    //region Command Reaction

    override fun mainInfo(Steps: Int, Calories: Int) {
        mLastStepsIncomed.postValue(Steps); mLastCcalsIncomed.postValue(Calories)
    }

    override fun batteryInfo(Charge: Int) {
        mbatteryHolder.postValue(Charge)
    }

    override fun hrIncome(Time: Calendar, HRValue: Int) {
        mLastHearthRateIncomed.postValue(HRRecord(Time, HRValue))
    }

    override fun hrHistoryRecord(Time: Calendar, HRValue: Int) {
        hrIncome(Time, HRValue)
    }

    override fun mainHistoryRecord(Time: Calendar, Steps: Int, Calories: Int) {
        //ignore
    }

    override fun sleepHistoryRecord(Time: Calendar, Duration: Int, Type: Int) {
        //ignore
    }

    //endregion

    companion object{
        var  publicModel: MainViewModel? = null
    }
}