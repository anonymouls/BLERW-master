package anonymouls.dev.mgcex.app.backend

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import android.view.View
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.MutableLiveData
import anonymouls.dev.mgcex.app.AlarmProvider
import anonymouls.dev.mgcex.app.R
import anonymouls.dev.mgcex.app.backend.ApplicationStarter.Companion.commandHandler
import anonymouls.dev.mgcex.app.main.ui.main.MainViewModel
import anonymouls.dev.mgcex.databaseProvider.*
import anonymouls.dev.mgcex.util.PreferenceListener
import anonymouls.dev.mgcex.util.ReplaceTable
import anonymouls.dev.mgcex.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.random.Random


@ExperimentalStdlibApi
class Algorithm : Service() {

    //region Properties

    private lateinit var database: DatabaseController
    private lateinit var prefs: SharedPreferences
    private var workInProgress = false
    private var isFirstTime = true
    private var connectionTries: Long = 0
    private var nextSyncMain: Calendar = Calendar.getInstance()
    private var nextSyncHR: Calendar? = null
    private var savedBattery: Short = 100
    private var disconnectedTimestamp: Long = System.currentTimeMillis()

    lateinit var ci: CommandInterpreter
    lateinit var uartService: UartService

    private lateinit var inserter: InsertTask
    private lateinit var lockedAddress: String
    private lateinit var wakeLock: PowerManager.WakeLock

    var bluetoothRejected = false
    var bluetoothRequested = false
    var thread: Thread? = null

    //endregion

    enum class StatusCodes(val code: Int) {
        Dead(-666), BluetoothDisabled(-2), DeviceLost(-1),
        Disconnected(0), Connected(10), Connecting(20), GattConnecting(21),
        GattConnected(30), GattDiscovering(40), GattReady(50)
    }

    private fun buildStatusMessage() {
        var result = ""
        if (checkPowerAlgo()) {
            result += getString(R.string.next_sync_status) +
                    SimpleDateFormat(Utils.SDFPatterns.TimeOnly.pattern,
                            Locale.getDefault()).format(nextSyncMain.time)


            if (nextSyncHR != null && prefs.getBoolean(PreferenceListener.Companion.PrefsConsts.hrMonitoringEnabled, false)) {
                result += "\n" + getString(R.string.hr_data_requested) +
                        SimpleDateFormat(Utils.SDFPatterns.TimeOnly.pattern,
                                Locale.getDefault()).format(nextSyncHR?.time)
            }
        } else {
            result += "\n" + getString(R.string.battery_low_status)
        }
        currentAlgoStatus.postValue(result)
    }

    //region Sync Utilities

    private fun getLastHRSync(): Calendar {
        return CustomDatabaseUtils.getLastSyncFromTable(HRRecordsTable.TableName,
                HRRecordsTable.ColumnsNames, true, database.readableDatabase)
    }

    private fun getLastMainSync(): Calendar {
        return CustomDatabaseUtils.getLastSyncFromTable(MainRecordsTable.TableName,
                MainRecordsTable.ColumnNames, true, database.readableDatabase)
    }

    private fun getLastSleepSync(): Calendar {
        return CustomDatabaseUtils.longToCalendar(SleepRecordsTable.getLastSync(database.readableDatabase), true)
    }

    private fun executeForceSync() {
        bluetoothRequested = false; bluetoothRejected = false
        GlobalScope.launch(Dispatchers.IO) { database.initRepairsAndSync(database.writableDatabase) }
        Handler(commandHandler.looper).postDelayed({ ci.requestSettings() }, 200)
        Handler(commandHandler.looper).postDelayed({ ci.requestBatteryStatus() }, 500)
        Handler(commandHandler.looper).postDelayed({ ci.syncTime(Calendar.getInstance()) }, 800)
        Handler(commandHandler.looper).postDelayed({ ci.requestHRHistory(getLastHRSync()) }, 1200)
        Handler(commandHandler.looper).postDelayed({ ci.requestSleepHistory(getLastSleepSync()) }, 1600)
        Handler(commandHandler.looper).postDelayed({ ci.getMainInfoRequest() }, 2200)
        if (isFirstTime) forceSyncHR()
        //if (IsAlarmingTriggered && !IsFromActivity) alarmTriggerDecider(0)
    }

    private fun forceSyncHR() {
        ci.hRRealTimeControl(true)
        //ci.requestManualHRMeasure(false)
        Handler(commandHandler.looper).postDelayed({ ci.hRRealTimeControl(false) }, 10000)
    }

    //endregion

    //region Background Taskforce

    private fun deadAlgo() {
        StatusCode.postValue(StatusCodes.Dead)
        IsActive = false
        inserter.stopInserter()
        SelfPointer = null
        uartService.disconnect()
        SelfPointer = null
        while (thread != null && thread?.state != Thread.State.TERMINATED
                && thread?.name != Thread.currentThread().name) {
            thread?.interrupt()
            Utils.safeThreadSleep(1000, true)
        }
        thread = null
        currentAlgoStatus.postValue(this.getString(R.string.status_label))
        this.stopForeground(true)
        this.stopSelf()
    }

    private fun run() {
        while (IsActive) {
            workInProgress = true
            when (StatusCode.value!!) {
                StatusCodes.DeviceLost -> Utils.safeThreadSleep(5*60*1000, false)
                StatusCodes.GattConnected -> connectedAlgo()
                StatusCodes.GattReady -> executeMainAlgo()
                StatusCodes.BluetoothDisabled -> bluetoothDisabledAlgo()
                StatusCodes.Connected, StatusCodes.Disconnected,
                StatusCodes.Connecting -> deviceDisconnectedAlgo()
                StatusCodes.GattConnecting, StatusCodes.GattDiscovering -> connectionAlgos()
                StatusCodes.Dead -> deadAlgo()
            }
        }
    }

    private fun executeMainAlgo() {
        if (!uartService.probeConnection()) return
        val syncPeriod = prefs.getString(PreferenceListener.Companion.PrefsConsts.mainSyncMinutes, "5")!!.toInt() * 60 * 1000
        nextSyncMain = Calendar.getInstance()
        nextSyncMain.add(Calendar.MILLISECOND, syncPeriod)

        if (checkPowerAlgo()) {
            connectionTries = 0
            currentAlgoStatus.postValue(getString(R.string.connected_syncing))
            executeForceSync()
            if ((!ci.hRRealTimeControlSupport && isFirstTime
                            && prefs.contains(PreferenceListener.Companion.PrefsConsts.hrMeasureInterval))
                    || ci.hRRealTimeControlSupport) {
                forceSyncHR()
                manualHRHack()
            }
            isFirstTime = false
            buildStatusMessage()
        }
        buildStatusMessage()
        workInProgress = false; MainViewModel.publicModel?.workInProgress?.postValue(View.GONE)
        Utils.safeThreadSleep(syncPeriod.toLong(), false)
        workInProgress = true; MainViewModel.publicModel?.workInProgress?.postValue(View.VISIBLE)
    }

    private fun bluetoothDisabledAlgo() {
        isFirstTime = true

        if (Utils.getSharedPrefs(this).getBoolean(PreferenceListener.Companion.PrefsConsts.permitWakeLock, true)) {
            if (this::wakeLock.isInitialized)
                wakeLock.acquire()
            else {
                wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                    newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MFitX::Tag").apply {
                        acquire()
                    }
                }
            }
        }

        if (Utils.bluetoothEngaging(this))
            StatusCode.postValue(StatusCodes.Disconnected)
        else {
            StatusCode.postValue(StatusCodes.BluetoothDisabled)
        }
        uartService.forceEnableBluetooth()
    }

    private fun deviceDisconnectedAlgo() {
        if (StatusCode.value!!.code < StatusCodes.GattConnecting.code) {
            connectionTries = System.currentTimeMillis()
            disconnectedTimestamp = System.currentTimeMillis()
            currentAlgoStatus.postValue(getString(R.string.conntecting_status))
            if (uartService.connect(lockedAddress)) {
                StatusCode.postValue(StatusCodes.GattConnecting)
            }
        }
    }

    private fun connectionAlgos() {
        if (System.currentTimeMillis() > connectionTries + 20000) {
            StatusCode.postValue(StatusCodes.Disconnected)
            uartService.disconnect()
            connectionTries = 0
        } else
            Utils.safeThreadSleep(25000, false)
    }

    private fun connectedAlgo() {
        currentAlgoStatus.postValue(getString(R.string.discovering))
        if (StatusCode.value!!.code < StatusCodes.GattDiscovering.code
                && StatusCode.value!!.code == StatusCodes.GattConnected.code) {
            connectionTries = System.currentTimeMillis()
            uartService.retryDiscovery()
            Utils.safeThreadSleep(21000, false)
        }
    }

    private fun checkPowerAlgo(): Boolean {
        return if (Utils.getSharedPrefs(this).getBoolean(PreferenceListener.Companion.PrefsConsts.batterySaverEnabled, true)) {
            val threshold = Utils.getSharedPrefs(this).getString(PreferenceListener.Companion.PrefsConsts.batteryThreshold, "20")!!.toInt()
            savedBattery !in 0..threshold
        } else true
    }

    //endregion

    //region Android

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        deadAlgo()
        if (this::inserter.isInitialized) inserter.stopInserter()
        this.stopForeground(true)
        SelfPointer = null
        sendBroadcast(Intent(MultitaskListener.restartAction))
    }

    override fun onCreate() {
        super.onCreate()
        GlobalScope.launch(Dispatchers.Default) { init() }
    }

    override fun stopService(name: Intent?): Boolean {
        deadAlgo()
        return super.stopService(name)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        GlobalScope.launch(Dispatchers.IO) { init() }
        return START_STICKY
    }

    fun enqueneData(sm: SimpleRecord) {
        if (sm.characteristic == UartService.PowerTXUUID.toString() ||
                sm.characteristic == UartService.PowerDescriptor.toString() ||
                sm.characteristic == UartService.PowerServiceUUID.toString()) {
            savedBattery = sm.Data[0].toShort()
            CommandCallbacks.getCallback(this).batteryInfo(sm.Data[0].toInt())
        } else {
            inserter.dataToHandle.add(sm)
            inserter.thread.interrupt()
        }
    }

    fun killWakeLock() {
        if (this::wakeLock.isInitialized) wakeLock.release()
    }

    fun sendData(Data: ByteArray): Boolean {
        return if (this::uartService.isInitialized) {
            this.uartService.writeRXCharacteristic(Data)
        } else false
    }

    //endregion

    //region Interacting

    fun killService(){
        deadAlgo()
    }

    fun init() {
        synchronized(IsActive) {
            if (!Utils.getSharedPrefs(this).contains(PreferenceListener.Companion.PrefsConsts.bandAddress)) {
                this.stopForeground(true)
                this.stopSelf()
                return
            } else {
                StatusCode.postValue(StatusCodes.Disconnected)
            }
            if (SelfPointer == null) {
                SelfPointer = this
                isFirstTime = true
                IsActive = true
                ReplaceTable.replaceString("", this)
                ci = CommandInterpreter.getInterpreter(this)
                ci.callback = CommandCallbacks.getCallback(this)
                inserter = InsertTask(ci)
                inserter.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR)
                prefs = Utils.getSharedPrefs(this)
                database = DatabaseController.getDCObject(this)
                if (!commandHandler.isAlive)
                    commandHandler.start()
                uartService = UartService(this)
                ServiceRessurecter.startJob(this)

                val service = Intent(this, NotificationService::class.java)
                if (!NotificationService.IsActive) {
                    Utils.serviceStartForegroundMultiAPI(service, this)
                }
                if (!isNotifyServiceAlive(this))
                    tryForceStartListener(this)
                lockedAddress = Utils.getSharedPrefs(this).getString(PreferenceListener.Companion.PrefsConsts.bandAddress, "").toString()
                Handler(Looper.getMainLooper()).post { StatusCode.value = StatusCodes.Disconnected }
                if (this::lockedAddress.isInitialized && lockedAddress.isNotEmpty()) {
                    if (Utils.getSharedPrefs(this).getBoolean(PreferenceListener.Companion.PrefsConsts.permitWakeLock, false)) {
                        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager).run {
                            newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MFitX::Tag").apply {
                                acquire()
                            }
                        }
                    }
                } else return
                thread = Thread(Runnable {
                    Thread.currentThread().name = "AASyncer"+ (Random.nextInt() % 50).toString()
                    Thread.currentThread().priority = Thread.MIN_PRIORITY
                    run()
                })
                thread?.start()

                getLastHRSync()
                getLastMainSync()
            }
        }
    }

    //endregion

    private fun manualHRHack() {
        if (StatusCode.value!!.code == StatusCodes.Dead.code
                || ci.hRRealTimeControlSupport) return
        val startString = prefs.getString(PreferenceListener.Companion.PrefsConsts.hrMeasureStart, "00:00")
        val endString = prefs.getString(PreferenceListener.Companion.PrefsConsts.hrMeasureEnd, "00:00")
        var targetString = Utils.subIntegerConversionCheck(Calendar.getInstance().get(Calendar.HOUR_OF_DAY).toString())
        targetString += ":"
        targetString += Utils.subIntegerConversionCheck(Calendar.getInstance().get(Calendar.MINUTE).toString())
        val isActive = if (startString == endString) true; else Utils.isTimeInInterval(startString!!, endString!!, targetString)
        if (isActive && prefs.getBoolean(PreferenceListener.Companion.PrefsConsts.hrMonitoringEnabled, false)
                && checkPowerAlgo()
                && StatusCode.value!!.code >= StatusCodes.GattReady.code) {
            ci.requestManualHRMeasure(false)
        }
        val interval = prefs.getString(PreferenceListener.Companion.PrefsConsts.hrMeasureInterval, "5")!!.toInt()
        this.nextSyncHR = Calendar.getInstance(); this.nextSyncHR?.add(Calendar.MINUTE, interval)
        Handler(commandHandler.looper).postDelayed({ manualHRHack() }, interval.toLong() * 60 * 1000)
        buildStatusMessage()
    }

    companion object {

        var SelfPointer: Algorithm? = null
        var StatusCode = MutableLiveData(StatusCodes.Disconnected)

        val currentAlgoStatus = MutableLiveData<String>(ApplicationStarter.appContext.getString(R.string.status_label))

        var ApproachingAlarm: AlarmProvider? = null
        var IsAlarmWaiting = false
        var IsAlarmingTriggered = false
        var IsAlarmKilled = false

        var IsActive = true

        fun isNotifyServiceAlive(context: Context): Boolean {
            val Names = NotificationManagerCompat.getEnabledListenerPackages(context)
            return Names.contains(context.packageName)
        }

        fun tryForceStartListener(context: Context) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(ComponentName(context, NotificationService::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            pm.setComponentEnabledSetting(ComponentName(context, NotificationService::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }

        fun updateStatusCode(newStatus: StatusCodes) {
            if (newStatus == StatusCodes.Dead) SelfPointer?.killService()
            if (StatusCode.value!!.code == StatusCodes.Dead.code) {
                return
            } else {
                StatusCode.postValue(newStatus)
                SelfPointer?.thread?.interrupt()
            }
        }
    }

}

// TODO Integrate data sleep visualization
// TODO Integrate 3d party services
// TODO Battery health tracker
// TODO LM: Other settings (dnd, alarms)