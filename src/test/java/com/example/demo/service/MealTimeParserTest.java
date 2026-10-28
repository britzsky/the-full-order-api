package com.example.demo.service;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** docs/MEAL_DOCUMENTS_RULES.md 2-5 해석 예시 표와 같은 내용 */
class MealTimeParserTest {
	private static LocalTime t(int h, int m) { return LocalTime.of(h, m); }

	@Test
	void picksNursingHomeTimeAmongGroups() {
		Map<Integer, MealTimeParser.SlotTime> r = MealTimeParser.resolve(null,
				"*죽,갈찬11:30 /*주간보호11:40  /*요양원11:45", "*주간보호15:40 /*갈찬16:00 /*요양원16:50", null);
		assertEquals(t(11, 45), r.get(MealTimeParser.LUNCH).time());
		assertEquals(t(16, 50), r.get(MealTimeParser.DINNER).time());
	}

	@Test
	void withoutNursingHomeUsesNonDayCare() {
		var r = MealTimeParser.resolve(null, "*주간보호11:40 /*갈찬11:50", null, null);
		assertEquals(t(11, 50), r.get(MealTimeParser.LUNCH).time());
		var allDayCare = MealTimeParser.resolve(null, "주간보호 11:40 / 주간보호2 11:35", null, null);
		assertEquals(t(11, 35), allDayCare.get(MealTimeParser.LUNCH).time());
	}

	@Test
	void splitsSnacksByTime() {
		var r = MealTimeParser.resolve(null, null, null, "*오전간식(조식때 같이제공) 7:20/ *오후간식14:00");
		assertEquals(t(7, 20), r.get(MealTimeParser.MORNING_SNACK).time());
		assertEquals("조식때 같이제공", r.get(MealTimeParser.MORNING_SNACK).note());
		assertEquals(t(14, 0), r.get(MealTimeParser.AFTERNOON_SNACK).time());
		var evening = MealTimeParser.resolve(null, null, null, "10:00 / 15:00 / 19:30");
		assertEquals(t(10, 0), evening.get(MealTimeParser.MORNING_SNACK).time());
		assertEquals(t(15, 0), evening.get(MealTimeParser.AFTERNOON_SNACK).time());
		assertEquals(t(19, 30), evening.get(MealTimeParser.EVENING_SNACK).time());
	}

	@Test
	void readsPlainTimesAndAmPm() {
		var r = MealTimeParser.resolve("07:30", "오후 1시 30분", "5:30", null);
		assertEquals(t(7, 30), r.get(MealTimeParser.BREAKFAST).time());
		assertEquals(t(13, 30), r.get(MealTimeParser.LUNCH).time());
		assertEquals(t(17, 30), r.get(MealTimeParser.DINNER).time());
		assertEquals(t(12, 0), MealTimeParser.resolve(null, "12시", null, null).get(MealTimeParser.LUNCH).time());
	}

	@Test
	void keepsTextWhenNoTime() {
		var r = MealTimeParser.resolve("조식 없음", null, "", "간식 수시");
		assertNull(r.get(MealTimeParser.BREAKFAST).time());
		assertEquals("조식 없음", r.get(MealTimeParser.BREAKFAST).text());
		assertFalse(r.containsKey(MealTimeParser.DINNER));
		assertFalse(r.containsKey(MealTimeParser.MORNING_SNACK));
	}
}
