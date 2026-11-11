package ua.dash.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Державні свята, пам'ятні та міжнародні дні (фіксовані дати + кілька «плаваючих»). */
object Holidays {
    private val fixed: Map<String, List<String>> = mapOf(
        "01-01" to listOf("Новий рік"),
        "01-04" to listOf("Всесвітній день азбуки Брайля"),
        "01-22" to listOf("День Соборності України"),
        "01-24" to listOf("Міжнародний день освіти"),
        "01-27" to listOf("День пам'яті жертв Голокосту"),
        "01-29" to listOf("День пам'яті героїв Крут"),
        "02-04" to listOf("Всесвітній день боротьби проти раку"),
        "02-10" to listOf("Всесвітній день бобових"),
        "02-11" to listOf("Міжнародний день жінок і дівчат у науці"),
        "02-13" to listOf("Всесвітній день радіо"),
        "02-14" to listOf("День святого Валентина"),
        "02-20" to listOf("День Героїв Небесної Сотні"),
        "02-21" to listOf("Міжнародний день рідної мови"),
        "02-24" to listOf("День пам'яті · початок повномасштабного вторгнення"),
        "02-26" to listOf("День спротиву окупації Криму"),
        "03-08" to listOf("Міжнародний жіночий день"),
        "03-09" to listOf("День народження Тараса Шевченка"),
        "03-14" to listOf("День числа π", "День українського добровольця"),
        "03-20" to listOf("Міжнародний день щастя"),
        "03-21" to listOf("Всесвітній день поезії"),
        "03-22" to listOf("Всесвітній день водних ресурсів"),
        "03-23" to listOf("Всесвітній метеорологічний день"),
        "03-27" to listOf("Міжнародний день театру"),
        "04-01" to listOf("День сміху"),
        "04-02" to listOf("Міжнародний день дитячої книги"),
        "04-07" to listOf("Всесвітній день здоров'я"),
        "04-12" to listOf("День космонавтики"),
        "04-22" to listOf("День Землі"),
        "04-23" to listOf("Всесвітній день книги"),
        "04-26" to listOf("День Чорнобильської трагедії"),
        "04-29" to listOf("Міжнародний день танцю"),
        "05-01" to listOf("День праці"),
        "05-03" to listOf("Всесвітній день свободи преси"),
        "05-08" to listOf("День пам'яті та перемоги над нацизмом"),
        "05-09" to listOf("День Європи"),
        "05-15" to listOf("Міжнародний день сім'ї"),
        "05-18" to listOf("День боротьби за права кримськотатарського народу", "Міжнародний день музеїв"),
        "05-20" to listOf("Всесвітній день бджіл"),
        "05-25" to listOf("День Африки"),
        "05-31" to listOf("Всесвітній день без тютюну"),
        "06-01" to listOf("Міжнародний день захисту дітей"),
        "06-05" to listOf("Всесвітній день довкілля"),
        "06-08" to listOf("Всесвітній день океанів"),
        "06-21" to listOf("Міжнародний день йоги", "День літнього сонцестояння"),
        "06-22" to listOf("День скорботи і вшанування пам'яті жертв війни"),
        "06-28" to listOf("День Конституції України"),
        "07-03" to listOf("День ВМС України"),
        "07-15" to listOf("День Української Державності"),
        "07-30" to listOf("Міжнародний день дружби"),
        "08-12" to listOf("Міжнародний день молоді"),
        "08-19" to listOf("Всесвітній день гуманітарної допомоги"),
        "08-23" to listOf("День Державного Прапора України"),
        "08-24" to listOf("День Незалежності України"),
        "08-29" to listOf("День пам'яті захисників України"),
        "09-01" to listOf("День знань"),
        "09-08" to listOf("Міжнародний день грамотності"),
        "09-21" to listOf("Міжнародний день миру"),
        "09-27" to listOf("Всесвітній день туризму"),
        "09-30" to listOf("Всеукраїнський день бібліотек", "Міжнародний день перекладача"),
        "10-01" to listOf("День захисників і захисниць України", "Міжнародний день музики"),
        "10-04" to listOf("Всесвітній день тварин"),
        "10-05" to listOf("Всесвітній день вчителя"),
        "10-10" to listOf("Всесвітній день психічного здоров'я"),
        "10-14" to listOf("Покрова"),
        "10-16" to listOf("Всесвітній день продовольства"),
        "10-24" to listOf("День ООН"),
        "10-31" to listOf("Хелловін"),
        "11-09" to listOf("День української писемності та мови"),
        "11-10" to listOf("Всесвітній день науки", "Всесвітній день молоді"),
        "11-13" to listOf("Всесвітній день доброти"),
        "11-16" to listOf("Міжнародний день толерантності"),
        "11-17" to listOf("Міжнародний день студентів"),
        "11-21" to listOf("День Гідності та Свободи"),
        "12-01" to listOf("Всесвітній день боротьби зі СНІДом"),
        "12-03" to listOf("Міжнародний день людей з інвалідністю"),
        "12-05" to listOf("Міжнародний день волонтера"),
        "12-06" to listOf("День Збройних Сил України", "День святого Миколая"),
        "12-10" to listOf("День прав людини"),
        "12-11" to listOf("Міжнародний день гір"),
        "12-14" to listOf("День вшанування ліквідаторів аварії на ЧАЕС"),
        "12-25" to listOf("Різдво Христове"),
        "12-31" to listOf("Переддень Нового року"),
    )

    private fun nth(year: Int, month: Int, dow: DayOfWeek, n: Int): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow))

    private fun last(year: Int, month: Int, dow: DayOfWeek): LocalDate =
        LocalDate.of(year, month, 1).with(TemporalAdjusters.lastInMonth(dow))

    /** Великдень (православний, григоріанська дата) */
    fun orthodoxEaster(year: Int): LocalDate {
        val a = year % 4
        val b = year % 7
        val c = year % 19
        val d = (19 * c + 15) % 30
        val e = (2 * a + 4 * b - d + 34) % 7
        val month = (d + e + 114) / 31
        val day = (d + e + 114) % 31 + 1
        return LocalDate.of(year, month, day).plusDays(13)
    }

    fun forDate(date: LocalDate): List<String> {
        val res = mutableListOf<String>()
        fixed["%02d-%02d".format(date.monthValue, date.dayOfMonth)]?.let { res.addAll(it) }
        val y = date.year
        val floating = mapOf(
            nth(y, 5, DayOfWeek.SUNDAY, 2) to "День матері",
            nth(y, 6, DayOfWeek.SUNDAY, 3) to "День батька",
            last(y, 6, DayOfWeek.SATURDAY) to "День молоді",
            nth(y, 9, DayOfWeek.SATURDAY, 2) to "День фізичної культури і спорту",
            last(y, 11, DayOfWeek.SATURDAY) to "День пам'яті жертв Голодоморів",
            nth(y, 10, DayOfWeek.SUNDAY, 1) to "День працівників освіти",
            last(y, 7, DayOfWeek.FRIDAY) to "День системного адміністратора",
            orthodoxEaster(y) to "Великдень",
            orthodoxEaster(y).plusDays(49) to "Трійця",
        )
        floating[date]?.let { res.add(it) }
        if (date.dayOfYear == 256) res.add("День програміста")
        return res.distinct()
    }
}
