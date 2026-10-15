package com.rs.lib.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

/** Requirement lists: grammar, comparisons and the fail-closed rules. */
class RequirementEngineTest {

    private final List<String> warnings = new ArrayList<>();

    private Requirement parse(String... lines) {
        return RequirementEngine.parse(Arrays.asList(lines), warnings::add);
    }

    private static Function<String, String> values(Map<String, String> map) {
        return map::get;
    }

    private static boolean test(Requirement requirement, Map<String, String> values) {
        return requirement.test(null, values(values));
    }

    @Test
    void emptyOrMissingListsAlwaysHold() {
        assertSame(Requirement.ALWAYS, RequirementEngine.parse(null, warnings::add));
        assertSame(Requirement.ALWAYS, RequirementEngine.parse(List.of(), warnings::add));
        assertSame(Requirement.ALWAYS, parse("", "   "));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void linesAreJoinedWithAnd() {
        Requirement r = parse("%a% >= 10", "%b% = yes");
        assertTrue(test(r, Map.of("%a%", "10", "%b%", "YES")));
        assertFalse(test(r, Map.of("%a%", "9", "%b%", "yes")));
        assertFalse(test(r, Map.of("%a%", "11", "%b%", "no")));
    }

    @Test
    void numbersCompareAsNumbers() {
        assertTrue(test(parse("%x% > 5"), Map.of("%x%", "5.5")));
        assertFalse(test(parse("%x% > 5"), Map.of("%x%", "5")));
        assertTrue(test(parse("%x% <= 5"), Map.of("%x%", "5.0")));
        assertTrue(test(parse("%x% < 5"), Map.of("%x%", "-7")));
        assertTrue(test(parse("%x% = 5"), Map.of("%x%", "5.00")));
        assertTrue(test(parse("%x% == 5"), Map.of("%x%", " 5 ")));
        assertTrue(test(parse("%x% != 5"), Map.of("%x%", "6")));
        assertTrue(test(parse("%x% >= 1e3"), Map.of("%x%", "1000")));
        assertTrue(warnings.isEmpty());
    }

    /** Trap: two-character operators are recognized before one-character ones. */
    @Test
    void twoCharacterOperatorsFirst() {
        assertTrue(test(parse("%x% >= 5"), Map.of("%x%", "5")));
        assertTrue(test(parse("%x%<=5"), Map.of("%x%", "5")));
        assertTrue(test(parse("%x% == abc"), Map.of("%x%", "ABC")));
        assertFalse(test(parse("%x% != abc"), Map.of("%x%", "abc")));
        assertTrue(warnings.isEmpty());
    }

    /** Trap: text equality ignores case; relational operators on text are false and reported once. */
    @Test
    void textComparisons() {
        assertTrue(test(parse("%world% = World_Nether"), Map.of("%world%", "world_nether")));
        assertFalse(test(parse("%world% != WORLD"), Map.of("%world%", "world")));
        Requirement relational = parse("%rank% > admin");
        assertFalse(test(relational, Map.of("%rank%", "zeus")));
        assertFalse(test(relational, Map.of("%rank%", "zeus")));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("compares text"), warnings.get(0));
        assertFalse(test(parse("%x% >= NaN"), Map.of("%x%", "NaN")));
        assertFalse(test(parse("%x% > 1d"), Map.of("%x%", "2d")));
    }

    @Test
    void andBindsTighterThanOrAndParenthesesGroup() {
        Requirement r = parse("%a% = 1 || %b% = 1 && %c% = 1");
        assertTrue(test(r, Map.of("%a%", "1", "%b%", "0", "%c%", "0")));
        assertFalse(test(r, Map.of("%a%", "0", "%b%", "1", "%c%", "0")));
        Requirement grouped = parse("(%a% = 1 || %b% = 1) && %c% = 1");
        assertFalse(test(grouped, Map.of("%a%", "1", "%b%", "0", "%c%", "0")));
        assertTrue(test(grouped, Map.of("%a%", "0", "%b%", "1", "%c%", "1")));
        Requirement doc = parse("%player_level% > 5 && (%player_world% = world || %player_world% = world_nether)");
        assertTrue(test(doc, Map.of("%player_level%", "6", "%player_world%", "world_nether")));
        assertFalse(test(doc, Map.of("%player_level%", "6", "%player_world%", "world_the_end")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** Trap: inside quotes connectors, parentheses and operators are text; one wrapping pair is removed. */
    @Test
    void quotedOperands() {
        Requirement doc = parse("'%luckperms_primary_group%' != 'default'");
        assertTrue(test(doc, Map.of("%luckperms_primary_group%", "vip")));
        assertFalse(test(doc, Map.of("%luckperms_primary_group%", "Default")));
        assertTrue(test(parse("%name% = \"a && b || (c) >= d\""), Map.of("%name%", "a && b || (c) >= d")));
        assertTrue(test(parse("%name% = '  padded  '"), Map.of("%name%", "  padded  ")));
        assertFalse(test(parse("%name% = '  padded  '"), Map.of("%name%", "padded")));
        assertTrue(test(parse("%name% = ''"), Map.of("%name%", "")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** Trap: a quote that is never closed runs to the end of the line. */
    @Test
    void unclosedQuoteRunsToTheEnd() {
        Requirement r = parse("%x% = 'a && %y% = b");
        assertTrue(test(r, Map.of("%x%", "a && %y% = b")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aQuoteInsideAnOperandIsText() {
        Requirement r = parse("%name% = O'Neil && %rank% = vip");
        assertTrue(test(r, Map.of("%name%", "o'neil", "%rank%", "VIP")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    /** Trap: a line written wrong fails closed, with a warning, and the whole list fails. */
    @Test
    void malformedLinesFailClosed() {
        String[] broken = {
            "(%a% = 1",           // parenthesis never closed
            "%a% = 1 &&",         // dangling connector
            "|| %a% = 1",         // connector without a condition
            "%vault_eco_balance%",// no operator
            "%a% >=",             // no right operand
            "= 5",                // no left operand
            "%a% = 1)",           // ')' without '('
            "%a% = 1 %b% = 2",    // two comparisons without a connector
            "()",                 // empty group
        };
        for (String line : broken) {
            warnings.clear();
            Requirement r = parse(line, "%ok% = 1");
            assertFalse(test(r, Map.of("%a%", "1", "%b%", "2", "%ok%", "1", "%vault_eco_balance%", "1")), line);
            assertEquals(1, warnings.size(), line + " -> " + warnings);
            assertTrue(warnings.get(0).contains("written wrong") && warnings.get(0).contains("always fails"),
                    warnings.get(0));
        }
    }

    @Test
    void operandsAreResolvedOnEveryCheckAndNullKeepsTheToken() {
        List<String> asked = new ArrayList<>();
        Requirement r = parse("%x% = %x%", "abc = abc", "{price} <= 10");
        Function<String, String> resolver = operand -> {
            asked.add(operand);
            return operand.equals("{price}") ? "5" : null;
        };
        assertTrue(r.test(null, resolver));
        assertTrue(r.test(null, resolver));
        // Constant operands are never resolved; placeholders on every check.
        assertEquals(List.of("%x%", "%x%", "{price}", "%x%", "%x%", "{price}"), asked);
        // Without a resolver the tokens stay as written: "{price}" is text, so "<=" is false.
        assertFalse(r.test(null, null));
    }

    @Test
    void engineResolvesLocalsBeforePapi() {
        FakeActionHost host = new FakeActionHost();
        host.papi = text -> text.replace("%vault_eco_balance%", "1500");
        ActionEngine engine = new ActionEngine(host);
        Requirement r = engine.requirements(List.of("%vault_eco_balance% >= {price}"), "guis/shop.yml",
                "items.buy.click-requirements");
        ActionContext context = ActionContext.of(null, null, Ph.of("price", "1000"));
        assertTrue(engine.test(r, context));
        assertFalse(engine.test(r, ActionContext.of(null, null, Ph.of("price", "2000"))));
        assertTrue(host.papiCalls.contains("%vault_eco_balance%"), host.papiCalls.toString());
        assertTrue(engine.test(Requirement.ALWAYS, context));
        assertTrue(engine.test(null, context));
    }

    /** Raul's rule: a requirement written wrong fails closed with a warning naming the file and key. */
    @Test
    void warningsNameFileAndKey() {
        FakeActionHost host = new FakeActionHost();
        ActionEngine engine = new ActionEngine(host);
        Requirement r = engine.requirements(List.of("%player_level% >"), "guis/shop.yml", "items.buy.click-requirements");
        assertFalse(engine.test(r, ActionContext.of(null, null)));
        assertEquals(1, host.warnings.size());
        assertTrue(host.warnings.get(0).startsWith("[guis/shop.yml] items.buy.click-requirements: "),
                host.warnings.get(0));
    }

    @Test
    void sectionValuesThatAreNotListsFailClosed() throws Exception {
        FakeActionHost host = new FakeActionHost();
        ActionEngine engine = new ActionEngine(host);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                items:
                  buy:
                    click-requirements: "%vault_eco_balance% >= 10"
                    view-requirements:
                      - [nested]
                    left-click-requirements:
                      - "%a% = 1"
                """);
        var buy = yaml.getConfigurationSection("items.buy");
        assertSame(Requirement.NEVER, engine.requirements(buy, "click-requirements", "guis/shop.yml"));
        assertSame(Requirement.NEVER, engine.requirements(buy, "view-requirements", "guis/shop.yml"));
        assertSame(Requirement.ALWAYS, engine.requirements(buy, "deny-requirements", "guis/shop.yml"));
        Requirement left = engine.requirements(buy, "left-click-requirements", "guis/shop.yml");
        assertTrue(left.test(null, Map.of("%a%", "1")::get));
        assertEquals(2, host.warnings.size(), host.warnings.toString());
        assertTrue(host.warnings.get(0).startsWith("[guis/shop.yml] items.buy.click-requirements: "),
                host.warnings.get(0));
        assertTrue(host.warnings.get(1).startsWith("[guis/shop.yml] items.buy.view-requirements: "),
                host.warnings.get(1));
    }

    @Test
    void numbersAreStrict() {
        assertEquals(5, RequirementEngine.number(" 5 "));
        assertEquals(-0.5, RequirementEngine.number("-.5"));
        assertEquals(1500, RequirementEngine.number("1.5e3"));
        assertTrue(Double.isNaN(RequirementEngine.number("1,000")));
        assertTrue(Double.isNaN(RequirementEngine.number("Infinity")));
        assertTrue(Double.isNaN(RequirementEngine.number("0x10")));
        assertTrue(Double.isNaN(RequirementEngine.number("5f")));
        assertTrue(Double.isNaN(RequirementEngine.number("")));
        assertTrue(Double.isNaN(RequirementEngine.number("1e")));
        assertTrue(Double.isNaN(RequirementEngine.number("1e999")));
    }
}
