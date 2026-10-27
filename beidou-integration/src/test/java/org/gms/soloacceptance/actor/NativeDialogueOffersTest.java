package org.gms.soloacceptance.actor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Only complete native offers become selectable IDs; route authorization is checked separately. */
class NativeDialogueOffersTest {
    @Test
    void readsEveryActuallyDisplayedOrdinaryCenterOfferInItsNativeOrder() {
        String text = "\t#e欢迎来到#rBeiDou#k脚本中心#n\r\n"
                + "当前金币：0\r\n"
                + "#L3#传送自由#l \t #L69#快速转职#l \t #L70#学习技能#l\r\n"
                + "#L71#超级传送#l \t #L4#爆率一览#l \t #L2#在线奖励#l\r\n"
                + "#L0#新人福利#l \t #L1#每日签到#l \t #L72#转世重生#l\r\n";

        NativeDialogueTracker.Offers offers = parse(text);

        assertTrue(offers.complete());
        assertEquals(List.of(3, 69, 70, 71, 4, 2, 0, 1, 72), offers.ids());
        assertFalse(offers.ids().contains(61));
        assertFalse(offers.ids().contains(68));
        assertFalse(offers.ids().contains(999));
        // Displayed IDs such as 69 remain offers; the reply path policy must still reject them.
        assertTrue(offers.ids().contains(69));
    }

    @Test
    void readsIdsWithoutRewritingMarkupOrInterpretingLabelsAsAdditionalChoices() {
        String text = "#b#L0##i2000000# #r福利#k（说明中的数字71不是选项）#l\r\n"
                + "#L71##e超级传送#n#l";
        Map<String, Object> event = event(text, false, 4);

        NativeDialogueTracker.Offers offers = NativeDialogueTracker.Offers.parse(event);

        assertTrue(offers.complete());
        assertEquals(List.of(0, 71), offers.ids());
        assertEquals(text, event.get("text"));
        assertThrows(UnsupportedOperationException.class, () -> offers.ids().add(999));
    }

    @Test
    void acceptsTheFullNonNegativeIntRangeOnlyWhenOffersAreComplete() {
        NativeDialogueTracker.Offers offers = parse("#L0#零#l#L2147483647#最大整数#l");

        assertTrue(offers.complete());
        assertEquals(List.of(0, Integer.MAX_VALUE), offers.ids());
    }

    @Test
    void rejectsMalformedNumericIdsInsteadOfCoercingThemToNativeSelections() {
        for (String id : List.of("-1", "+1", "1.0", "1e2", "true", " ", "", "١", "０",
                "2147483648", "999999999999999999999999999999999999")) {
            assertIncomplete("#L" + id + "#不可选择#l");
        }
    }

    @Test
    void rejectsUnclosedNestedOrStrayMarkersWithoutReturningAPartialAllowlist() {
        for (String text : List.of(
                "#l",
                "#l#L0#后面的合法项也不能掩盖错误#l",
                "#L0#缺少结束标记",
                "#L0 缺少数字分隔符#l",
                "#L0#外层#L1#内层#l#l",
                "#L0#合法项#l#L1#后项不完整",
                "#L0#合法项#l#l")) {
            assertIncomplete(text);
        }
    }

    @Test
    void rejectsDuplicateIdsIncludingDifferentDecimalSpellingsOfTheSameSelection() {
        assertIncomplete("#L0#第一项#l#L0#重复项#l");
        assertIncomplete("#L0#零#l#L00#同一个零#l");
        assertIncomplete("#L001#一#l#L1#同一个一#l");
    }

    @Test
    void treatsAnEmptyOrInformationalMenuAsCompleteButWithNoSelectableIds() {
        for (String text : List.of("", "#e提示#n：这段文字没有选择项。\r\n数字0和71只是说明。")) {
            NativeDialogueTracker.Offers offers = parse(text);
            assertTrue(offers.complete());
            assertEquals(List.of(), offers.ids());
        }
    }

    @Test
    void boundsOfferMetadataAt128AndRejectsOverflowAsAWhole() {
        // Parser seam only: the real packet decoder independently enforces the 1024-codepoint view.
        String maximum = menuWithChoices(128);
        NativeDialogueTracker.Offers offers = parse(maximum);
        assertTrue(offers.complete());
        assertEquals(IntStream.range(0, 128).boxed().toList(), offers.ids());

        assertIncomplete(maximum + "#L128#第129项#l");
        assertIncomplete(menuWithChoices(129));
    }

    @Test
    void neverTrustsTruncatedMenusEvenWhenTheRequestedChoiceWouldBeInTheVisiblePrefix() {
        for (String text : List.of("#L1#射手村#l", "#L0#已显示#l#L1#只显示开头")) {
            NativeDialogueTracker.Offers offers = NativeDialogueTracker.Offers.parse(event(text, true, 4));
            assertFalse(offers.complete());
            assertEquals(List.of(), offers.ids());
        }
    }

    @Test
    void acceptDeclineMessagesDoNotInventSelectionsFromTheirText() {
        NativeDialogueTracker.Offers confirmation = NativeDialogueTracker.Offers.parse(
                event("您确定要领取新手礼包吗？#b500#k万金币。#L0#正文也不是菜单#l", false, 12));
        assertTrue(confirmation.complete());
        assertEquals(List.of(), confirmation.ids());

        NativeDialogueTracker.Offers truncated = NativeDialogueTracker.Offers.parse(
                event("未完整展示的确认正文", true, 12));
        assertFalse(truncated.complete());
        assertEquals(List.of(), truncated.ids());
    }

    @Test
    void missingTextOrExplicitCompletenessCannotProduceAnOfferAllowlist() {
        for (Map<String, Object> event : List.of(
                Map.<String, Object>of("message_type", 4, "text_truncated", false),
                Map.<String, Object>of("message_type", 4, "text", "#L0#未声明是否截断#l"),
                Map.<String, Object>of("message_type", 4, "text", 0, "text_truncated", false))) {
            NativeDialogueTracker.Offers offers = NativeDialogueTracker.Offers.parse(event);
            assertFalse(offers.complete());
            assertEquals(List.of(), offers.ids());
        }
    }

    private static NativeDialogueTracker.Offers parse(String text) {
        return NativeDialogueTracker.Offers.parse(event(text, false, 4));
    }

    private static Map<String, Object> event(String text, boolean truncated, int type) {
        return Map.of("message_type", type, "text", text, "text_truncated", truncated);
    }

    private static String menuWithChoices(int count) {
        return IntStream.range(0, count).mapToObj(id -> "#L" + id + "#选项#l").collect(Collectors.joining());
    }

    private static void assertIncomplete(String text) {
        NativeDialogueTracker.Offers offers = parse(text);
        assertFalse(offers.complete(), () -> "ambiguous menu was accepted: " + text);
        assertEquals(List.of(), offers.ids(), "a malformed menu must not expose a partial allowlist");
    }
}
