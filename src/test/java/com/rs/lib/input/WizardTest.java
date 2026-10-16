package com.rs.lib.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.rs.lib.Ph;

/** The wizard's state machine without a server: steps, going back, every ending, ending once. */
class WizardTest {

    private FakeInputHost host;
    private Inputs inputs;
    private final boolean[] online = {true};
    private Player alex;
    private final List<WizardAnswers> confirmed = new ArrayList<>();
    private final List<WizardEnd> ends = new ArrayList<>();
    private final List<String> order = new ArrayList<>();

    @BeforeEach
    void setUp() {
        host = new FakeInputHost();
        inputs = new Inputs(host);
        alex = FakeInputHost.player(online);
    }

    @AfterEach
    void tearDown() {
        Inputs.ACTIVE.clear();
    }

    /** id (text 3-8), slots (1-54), period (daily / weekly). */
    private Wizard kit() {
        return inputs.wizard(alex)
                .text("id", 3, 8, "ask.id")
                .whole("slots", 1, 54, "ask.slots")
                .choice("period", List.of("daily", "weekly"), "ask.period")
                .onConfirm(answers -> {
                    order.add("confirm");
                    confirmed.add(answers);
                })
                .onEnd(end -> {
                    order.add("end");
                    ends.add(end);
                });
    }

    private void say(String text) {
        assertTrue(Inputs.capture(alex, text), "no open question took '" + text + "'");
        host.tick();
    }

    private long count(String prefix) {
        return host.sent.stream().filter(line -> line.startsWith(prefix)).count();
    }

    @Test
    void stepsSummaryAndConfirm() {
        Wizard wizard = kit().start();
        assertEquals(0, wizard.step());
        assertTrue(host.sent.contains("rslib.wizard.step step=1 steps=3"));
        assertTrue(host.sent.contains("ask.id"));
        say("ab");
        assertTrue(host.sent.contains("rslib.input.too-short min=3 max=8 value=ab"), "a refused answer is asked again");
        assertEquals(0, wizard.step());
        say("starter");
        say("2k");
        assertTrue(host.sent.contains("rslib.out-of-range min=1 max=54 value=2k"));
        say("27");
        assertTrue(host.sent.contains("rslib.wizard.options options=daily, weekly"));
        say("2");
        assertEquals(3, wizard.step(), "every step answered: the summary waits");
        assertTrue(host.sent.contains("rslib.wizard.summary"));
        assertTrue(host.sent.contains("rslib.wizard.summary-line name=slots value=27"));
        assertTrue(host.sent.contains("rslib.wizard.summary-line name=period value=weekly"));
        assertTrue(confirmed.isEmpty(), "nothing is applied before the confirm");
        assertTrue(ends.isEmpty());

        say("maybe");
        assertTrue(host.sent.contains("rslib.invalid-value value=maybe"));
        assertTrue(confirmed.isEmpty());
        say("CONFIRMAR");
        assertEquals(List.of(WizardEnd.CONFIRMED), ends);
        assertEquals(List.of("confirm", "end"), order, "onConfirm runs before onEnd");
        WizardAnswers answers = confirmed.get(0);
        assertEquals("starter", answers.text("id"));
        assertEquals(27L, answers.whole("slots"));
        assertEquals("weekly", answers.text("period"));
        assertEquals(List.of("id", "slots", "period"), answers.keys());
        assertTrue(wizard.isEnded());
        assertEquals(WizardEnd.CONFIRMED, wizard.endReason());
        assertFalse(Inputs.capture(alex, "more"), "an ended wizard holds no question");
        assertTrue(host.timers.stream().allMatch(t -> t.cancelled), "a step timer kept running");
    }

    @Test
    void goingBackOneStep() {
        Wizard wizard = kit().start();
        say("back");
        assertEquals(0, wizard.step(), "back on the first step asks it again");
        assertEquals(2, count("ask.id"));
        assertEquals(0, count("rslib.wizard.back-hint"), "no hint on the first step");
        say("starter");
        assertEquals(1, count("rslib.wizard.back-hint"));
        say("Atrás");
        assertEquals(0, wizard.step());
        assertFalse(wizard.answers().has("id"), "the answer of the step asked again is dropped");
        say("vip");
        say("9");
        say("daily");
        assertEquals(3, wizard.step());
        say("back");
        assertEquals(2, wizard.step(), "back from the summary changes the last answer");
        assertFalse(wizard.answers().has("period"));
        assertEquals("vip", wizard.answers().text("id"));
        say("1");
        say("confirm");
        assertEquals(List.of(WizardEnd.CONFIRMED), ends);
        assertEquals("vip", confirmed.get(0).text("id"));
        assertEquals("daily", confirmed.get(0).text("period"));
    }

    @Test
    void aStepTimeoutEndsEverything() {
        Wizard wizard = kit().stepTimeout(Duration.ofSeconds(10)).start();
        assertEquals(200L, host.timers.get(0).ticks);
        say("starter");
        assertEquals(200L, host.timers.get(1).ticks, "each step gets its own timeout");
        host.fireTimer();
        assertEquals(List.of(WizardEnd.TIMED_OUT), ends);
        assertTrue(confirmed.isEmpty());
        assertEquals(1, count("rslib.input.timed-out"));
        assertFalse(Inputs.capture(alex, "27"));
        assertFalse(wizard.cancel(), "it already ended");
        assertEquals(List.of(WizardEnd.TIMED_OUT), ends);
    }

    @Test
    void quittingEndsItOnce() {
        Wizard wizard = kit().start();
        say("starter");
        online[0] = false;
        inputs.left(alex.getUniqueId());
        assertEquals(List.of(WizardEnd.LEFT), ends);
        assertEquals(WizardEnd.LEFT, wizard.endReason());
        assertFalse(inputs.isWaiting(alex.getUniqueId()), "the open question was released");
        assertTrue(host.timers.stream().allMatch(t -> t.cancelled));
        inputs.shutdown();
        assertEquals(List.of(WizardEnd.LEFT), ends, "a later ending does nothing");
        assertEquals(0, count("rslib.input.cancelled"));
    }

    @Test
    void doubleEndRunsTheEndingOnce() {
        Wizard wizard = kit().start();
        assertTrue(wizard.cancel());
        assertFalse(wizard.cancel());
        assertFalse(wizard.end(WizardEnd.SHUT_DOWN));
        assertFalse(wizard.end(WizardEnd.CONFIRMED));
        assertEquals(List.of(WizardEnd.CANCELLED), ends);
        assertEquals(WizardEnd.CANCELLED, wizard.endReason());
        assertTrue(confirmed.isEmpty());
        assertEquals(1, count("rslib.input.cancelled"), "the open question is ended once, as cancelled");
        assertFalse(Inputs.capture(alex, "starter"));
        host.tick();
        assertEquals(List.of(WizardEnd.CANCELLED), ends);
    }

    @Test
    void theCancelWordAndShutdown() {
        kit().start();
        say("starter");
        say("CANCEL");
        assertEquals(List.of(WizardEnd.CANCELLED), ends);
        assertTrue(confirmed.isEmpty());

        ends.clear();
        Wizard second = kit().cancelWords("stop").start();
        say("cancel");
        assertEquals(1, count("rslib.input.cancelled"), "'cancel' is a valid id when it is not a cancel word");
        assertEquals(1, second.step());
        inputs.shutdown();
        assertEquals(List.of(WizardEnd.SHUT_DOWN), ends);
        assertFalse(Inputs.capture(alex, "1"));
        ends.clear();
        Wizard late = kit().start();
        assertEquals(WizardEnd.SHUT_DOWN, late.endReason(), "nothing starts after a shutdown");
        assertEquals(List.of(WizardEnd.SHUT_DOWN), ends);
    }

    @Test
    void anotherQuestionReplacesTheWizard() {
        kit().start();
        new Inputs(host).text(alex).open();
        assertEquals(List.of(WizardEnd.REPLACED), ends);
        ends.clear();
        kit().start();
        Wizard newer = kit().start();
        assertEquals(List.of(WizardEnd.REPLACED), ends, "a second wizard ends the first");
        assertFalse(newer.isEnded());
    }

    @Test
    void aFailingCallbackStillReleasesTheRest() {
        Wizard wizard = inputs.wizard(alex).text("id", 1, 8, "ask.id")
                .onConfirm(answers -> {
                    throw new IllegalStateException("boom");
                })
                .onEnd(ends::add)
                .start();
        say("x");
        say("confirm");
        assertEquals(List.of(WizardEnd.CONFIRMED), ends, "onEnd runs after a failing onConfirm");
        assertTrue(host.severe.stream().anyMatch(line -> line.contains("confirm action")));
        assertTrue(wizard.isEnded());
        assertFalse(inputs.isWaiting(alex.getUniqueId()));
    }

    @Test
    void answersAreShownAsPlainText() {
        inputs.wizard(alex).text("name", 1, 32, "ask.name").amount("price", BigDecimal.ZERO, null, "ask.price")
                .onEnd(ends::add).start();
        say("&cRed <bold>x</bold>");
        say("1.50");
        int line = host.sent.indexOf("rslib.wizard.summary-line name=name value=&cRed <bold>x</bold>");
        assertTrue(line >= 0, host.sent.toString());
        for (Ph ph : host.sentPhs.get(line)) {
            assertTrue(ph.literal(), "player text must be literal: " + ph);
        }
        assertTrue(host.sent.contains("rslib.wizard.summary-line name=price value=1.5"));
        assertTrue(host.sent.contains("rslib.wizard.confirm confirm=confirm/confirmar back=back/atras/atrás"
                + " cancel=cancel/cancelar"));
    }

    @Test
    void choiceAndBuilderChecks() throws InputRejected {
        List<String> options = List.of("Daily", "weekly");
        assertEquals("Daily", Wizard.choose(options, "daily"));
        assertEquals("weekly", Wizard.choose(options, "2"));
        assertThrows(InputRejected.class, () -> Wizard.choose(options, "3"));
        assertThrows(InputRejected.class, () -> Wizard.choose(options, "monthly"));
        assertThrows(IllegalArgumentException.class, () -> inputs.wizard(alex).choice("p", List.of("a", "A"), "k"));
        assertThrows(IllegalArgumentException.class, () -> inputs.wizard(alex).choice("p", List.of(), "k"));
        assertThrows(IllegalArgumentException.class,
                () -> inputs.wizard(alex).text("a", 1, 2, "k").whole("a", 1, 2, "k"));
        assertThrows(IllegalStateException.class, () -> inputs.wizard(alex).start(), "no steps");
        assertThrows(IllegalArgumentException.class, () -> inputs.wizard(alex).confirmWords());
        Wizard started = inputs.wizard(alex).text("a", 1, 2, "k").start();
        assertThrows(IllegalStateException.class, started::start);
        assertThrows(IllegalStateException.class, () -> started.text("b", 1, 2, "k"));
        host.owns = false;
        assertThrows(IllegalStateException.class, () -> inputs.wizard(alex).text("a", 1, 2, "k").start());
        assertNull(inputs.wizard(alex).endReason());
    }
}
