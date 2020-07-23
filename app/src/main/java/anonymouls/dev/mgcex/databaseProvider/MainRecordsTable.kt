package anonymouls.dev.mgcex.databaseProvider

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import anonymouls.dev.mgcex.app.backend.ApplicationStarter
import anonymouls.dev.mgcex.app.data.DataFragment
import anonymouls.dev.mgcex.app.main.SettingsActivity
import anonymouls.dev.mgcex.util.Utils
import java.lang.Math.abs
import java.util.*

@ExperimentalStdlibApi
object MainRecordsTable {

    const val TableName = "MainRecords"

    var ColumnNames = arrayOf("ID", "Date", "Steps", "Calories")
    var ColumnsForExtraction = arrayOf("Date", "Steps", "Calories")

    var ColumnNamesCloneAdditional = arrayListOf("Analyzed")

    fun getCreateTableCommandClone(): String {
        return "CREATE TABLE if not exists " + TableName + "COPY(" +
                ColumnNames[0] + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                ColumnNames[1] + " INTEGER UNIQUE, " +
                ColumnNames[2] + " INTEGER, " +
                ColumnNames[3] + " INTEGER," +
                ColumnNamesCloneAdditional[0] + " INTEGER default 0" +
                ");"
    }

    fun getCreateTableCommand(): String {
        return "create table if not exists " + MainRecordsTable.TableName + "(" +
                ColumnNames[0] + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                ColumnNames[1] + " INTEGER UNIQUE, " +
                ColumnNames[2] + " INTEGER, " +
                ColumnNames[3] + " INTEGER" +
                ");"
    }

//region data inserting

    fun getDeltaInMinutes(currentTime: Calendar, Operator: SQLiteDatabase): Long {
        val curs = Operator.query(MainRecordsTable.TableName,
                arrayOf(ColumnNames[1]), null, null, null, null, ColumnNames[1] + " DESC", "1")
        curs.moveToFirst()
        if (curs.count > 0) {
            val nearestCalendar = CustomDatabaseUtils.longToCalendar(curs.getLong(0), true)
            curs.close()
            val diff: Long = currentTime.timeInMillis - nearestCalendar.timeInMillis
            val seconds = diff / 1000
            val minutes = seconds / 60
            return abs(minutes)
        } else return -1
    }

    private fun checkIsExistsToday(TimeRecord: Calendar, Operator: SQLiteDatabase, Steps: Int, Calories: Int): Long? {
        TimeRecord.set(Calendar.HOUR_OF_DAY, 0)
        TimeRecord.set(Calendar.MINUTE, 0)
        val from = CustomDatabaseUtils.calendarToLong(TimeRecord, true)
        val to = from + 2359

        val curs = Operator.query(MainRecordsTable.TableName, arrayOf(ColumnNames[1], ColumnNames[2], ColumnNames[3]),
                " " + ColumnNames[1] + " BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()), null, null, null, "1")

        return if (curs.count > 0) {
            curs.moveToFirst()
            if (curs.getLong(1) > Steps)
                throw Exception("Corrupted Data")
            val result = curs.getLong(0); curs.close(); result
        } else null
    }

    fun insertRecordV2(TimeRecord: Calendar, Steps: Int, Calories: Int, Operator: SQLiteDatabase): Long {
        if (TimeRecord.time > Calendar.getInstance().time) return -1
        val Values = ContentValues()
        val recordDate = CustomDatabaseUtils.calendarToLong(TimeRecord, true)
        Values.put(ColumnNames[1], recordDate)
        Values.put(ColumnNames[2], Steps)
        Values.put(ColumnNames[3], Calories)
        writeIntermediate(MainRecord(TimeRecord, Steps, Calories), Operator)
        try {
            val curs = Operator.query(TableName + "COPY", arrayOf(ColumnNames[1]), ColumnNames[1] + " = ?",
                    arrayOf(recordDate.toString()), null, null, null)
            if (curs.count == 0)
                Operator.insert(TableName + "COPY", null, Values)
            curs.close()
        } catch (ex: Exception) {
        }
        return try {
            val checker = checkIsExistsToday(TimeRecord, Operator, Steps, Calories)
            if (checker == null)
                Operator.insert(MainRecordsTable.TableName, null, Values)
            else {
                updateExisting(Values, checker, Operator)
                checker
            }
        } catch (ex: Exception) {
            -1
        }
    }

    private fun updateExisting(values: ContentValues, targetTime: Long, Operator: SQLiteDatabase) {
        Operator.update(MainRecordsTable.TableName, values, " " + ColumnNames[1] + " = ?", arrayOf(targetTime.toString()))
    }

//endregion

    fun extractRecords(From: Long, To: Long, Operator: SQLiteDatabase, scaling: DataFragment.Scalings): Cursor {
        val tableName = if (scaling == DataFragment.Scalings.Day) MainRecordsTable.TableName + "COPY" else MainRecordsTable.TableName
        val record = Operator.query(tableName, ColumnsForExtraction,
                ColumnNames[1] + " BETWEEN ? AND ?", arrayOf(From.toString(), To.toString()), null, null, ColumnNames[1])
        record.moveToFirst()
        return record
    }

    fun extractFuncOnIntervalSteps(Where: Long, To: Long, Operator: SQLiteDatabase): Cursor {
        //val tableName = if (scaling == DataView.Scalings.Day) MainRecordsTable.TableName+"COPY" else MainRecordsTable.TableName
        val Record = Operator.query(
                TableName,
                arrayOf("AVG(Steps)", "MIN(Steps)", "MAX(Steps)"),
                ColumnNames[1] + " BETWEEN ? AND ?", arrayOf(Where.toString(), To.toString()), null, null, ColumnNames[1])
        Record.moveToFirst()
        return Record
    }

    fun extractFuncOnIntervalCalories(Where: Long, To: Long, Operator: SQLiteDatabase): Cursor {
        val Record = Operator.query(
                MainRecordsTable.TableName,
                arrayOf("AVG(Calories)", "MIN(Calories)", "MAX(Calories)"),
                ColumnNames[1] + " BETWEEN ? AND ?", arrayOf(Where.toString(), To.toString()), null, null, ColumnNames[1])
        Record.moveToFirst()
        return Record
    }

    fun generateReport(From: Calendar?, To: Calendar?, Operator: SQLiteDatabase, context: Context?): MainReport {
        val from: Long
        val to: Long
        if (From == null || To == null) {
            from = 0
            to = Long.MAX_VALUE
        } else {
            from = CustomDatabaseUtils.calendarToLong(From, true)
            to = CustomDatabaseUtils.calendarToLong(To, true)
        }
        val curs = Operator.query(MainRecordsTable.TableName,
                arrayOf(CustomDatabaseUtils.niceSQLFunctionBuilder("COUNT", "*"),
                        CustomDatabaseUtils.niceSQLFunctionBuilder("SUM", ColumnNames[2]),
                        CustomDatabaseUtils.niceSQLFunctionBuilder("SUM", ColumnNames[3])),
                ColumnNames[1] + " BETWEEN ? AND ?", arrayOf(from.toString(), to.toString()), null, null, null)
        curs.moveToFirst()
        val result = MainReport(curs.getInt(1), curs.getInt(2), curs.getInt(0), null, context)
        curs.close()
        return result
    }

//region advanced tracking

    private fun extractFreshRecord(limiter: Long, db: SQLiteDatabase): MainRecord {
        val curs = db.query(MainRecordsTable.TableName, ColumnsForExtraction,
                "DATE < $limiter", null, null, null, ColumnNames[1] + " desc", "1")
        return if (curs.count > 0) {
            curs.moveToFirst()
            val result = MainRecord(CustomDatabaseUtils.longToCalendar(curs.getLong(0), true), curs.getInt(1), curs.getInt(2)); curs.close(); result
        } else {
            curs.close()
            val calendar = Calendar.getInstance(); calendar.set(Calendar.HOUR_OF_DAY, 0); MainRecord(calendar, 0, 0)
        }
    }

    private fun writeIntermediate(newData: MainRecord, db: SQLiteDatabase) {
        if (newData.RTime.time > Calendar.getInstance().time) return
        val freshData = extractFreshRecord(CustomDatabaseUtils.calendarToLong(newData.RTime, true), db)
        val deltaMinutes = kotlin.math.abs(Utils.getDeltaCalendar(freshData.RTime, newData.RTime, Calendar.MINUTE))
        val deltaSteps = newData.Steps - freshData.Steps
        val speed = if (deltaMinutes.toInt() != 0) deltaSteps.toDouble() / deltaMinutes else deltaSteps.toDouble() / 1
        if (deltaSteps > 0 && (deltaMinutes in 5..120)) {
            AdvancedActivityTracker.insertRecord(freshData.RTime, deltaMinutes.toInt(), speed, db)
            HRRecordsTable.updateAnalyticalViaMainInfo(deltaMinutes, speed,
                    CustomDatabaseUtils.calendarToLong(freshData.RTime, true), db)
        } else {
            AdvancedActivityTracker.insertRecord(newData.RTime, -1, -1.0, db)
        }
    }

//endregion

    class MainRecord(val RTime: Calendar, val Steps: Int, val Calories: Int)

    @ExperimentalStdlibApi
    class MainReport(var stepsCount: Int = -1, var caloriesCount: Int = -1, var recordsCount: Int = -1, var analytics: String? = null,
                     private val context: Context?) {
        var passedKm: Float = 0.0f

        init {
            passedKm =
                    if (context != null)
                        Utils.getSharedPrefs(ApplicationStarter.appContext).getString(SettingsActivity.stepsSize, "0.5")!!.replace(',', '.').toFloat() * stepsCount
                    else
                        Utils.getSharedPrefs(ApplicationStarter.appContext).getString(SettingsActivity.stepsSize, "0.5")!!.replace(',', '.').toFloat() * stepsCount
        }
    }

    const val SharedPrefsMainCollapsedConst = "LastMainDataCollapsed"
}