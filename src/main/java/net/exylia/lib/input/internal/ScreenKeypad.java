package net.exylia.lib.input.internal;

import net.exylia.lib.input.AmountInput;
import net.exylia.lib.input.InputOutcome;
import net.exylia.lib.input.InputParser;
import net.exylia.lib.input.InputRequest;
import net.exylia.lib.input.NumberInput;
import net.exylia.lib.screen.Motion;
import net.exylia.lib.screen.Node;
import net.exylia.lib.screen.ScreenSession;
import net.exylia.lib.text.Phrases;
import net.exylia.lib.text.Text;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Numbers and amounts for a player on screens: a keypad drawn on the screen instead of a dialog. Digits, a point
 * and the K, M and B suffixes the parser already reads ({@code 1.7m}), quick steps ({@code +1K}, {@code x2},
 * {@code Max}), the value read back in full under what was typed, and a "Type it" button that hands the request to
 * the dialog for whoever would rather write it.
 *
 * @since 1.607.0
 */
final class ScreenKeypad {

    static final String VALUE_KEY = "value";
    private static final int LONGEST = 16;
    private static final BigDecimal[] UNITS = {
            BigDecimal.valueOf(1_000_000_000L), BigDecimal.valueOf(1_000_000L), BigDecimal.valueOf(1_000L)};
    private static final String[] SUFFIXES = {"B", "M", "K"};

    private final InputSession session;
    private final InputRequest<?, ?> request;
    private final boolean whole;
    private final @Nullable BigDecimal minimum;
    private final @Nullable BigDecimal maximum;
    private final Runnable typeIt;

    ScreenKeypad(InputSession session, InputRequest<?, ?> request, Runnable typeIt) {
        this.session = session;
        this.request = request;
        this.typeIt = typeIt;
        this.whole = request.parser() == InputParser.integer();
        this.minimum = request instanceof AmountInput a ? a.minimum() : request instanceof NumberInput<?> n ? decimal(n.minimum()) : null;
        this.maximum = request instanceof AmountInput a ? a.maximum() : request instanceof NumberInput<?> n ? decimal(n.maximum()) : null;
    }

    /** Whether this request is a number the keypad can take. */
    static boolean handles(InputRequest<?, ?> request) {
        return request instanceof AmountInput || request instanceof NumberInput<?>;
    }

    /** What the keypad starts with: the request's current value, written short. */
    String opening() {
        Object current = request.defaultValue();
        return current == null ? "" : compact(new BigDecimal(current.toString()));
    }

    Node draw(ScreenSession screen) {
        String typed = screen.state(VALUE_KEY, opening());
        InputParser.Parsed<?> parsed = typed.isEmpty() ? null : request.parseRaw(typed);
        boolean ready = parsed != null && parsed.ok();

        String shown = typed.isEmpty() ? "{muted}0" : "{letters}" + typed;
        String under = parsed == null ? "{letters_black}" + Phrases.tr("Use K, M or B for thousands, millions, billions.")
                : ready ? "{letters_black}= {success}" + full(new BigDecimal(parsed.value().toString()))
                : "{error}" + parsed.error();
        Node display = Node.column(Node.text(Text.of(shown).build()).scale(1.6),
                        Node.text(Text.of(under).build()).scale(0.6).wrap(200).textAlign(Node.Align.CENTER))
                .size(176, 36).padding(4).gap(2).align(Node.Align.CENTER).justify(Node.Justify.CENTER)
                .background("#0f0c16").outline(ready ? "{primary}60" : "{letters_black}30", 1);

        Node pad = Node.column().gap(3);
        String[][] keys = {{"7", "8", "9", "K"}, {"4", "5", "6", "M"}, {"1", "2", "3", "B"}, {".", "0", "⌫", "C"}};
        for (String[] line : keys) {
            Node row = Node.row().gap(3);
            for (String key : line) row.add(key(screen, typed, key));
            pad.add(row);
        }

        Node quick = Node.column().gap(3);
        BigDecimal small = whole ? BigDecimal.ONE : BigDecimal.valueOf(1_000L);
        BigDecimal big = whole ? BigDecimal.TEN : BigDecimal.valueOf(10_000L);
        quick.add(step(screen, "+" + compact(small), apply(typed, value -> value.add(small))));
        quick.add(step(screen, "+" + compact(big), apply(typed, value -> value.add(big))));
        quick.add(step(screen, "×2", apply(typed, value -> value.multiply(BigDecimal.TWO))));
        if (maximum != null) quick.add(step(screen, Phrases.tr("Max"), compact(maximum)));
        if (signed()) quick.add(step(screen, "±", typed.startsWith("-") ? typed.substring(1) : typed.isEmpty() ? "-" : "-" + typed));

        Node confirm = Node.text(Text.of((ready ? "{letters}" : "{muted}") + Phrases.tr("Confirm")).build()).padding(4, 12)
                .background(ready ? "{primary}" : "{letters_black}20").hover(ready ? "{secondary}" : null).key("space")
                .active(null, Motion.Transform.scale(0.95))
                .onClick(event -> {
                    if (ready) session.complete(parsed.value());
                });
        Node foot = Node.row(button(Phrases.tr("Cancel"), () -> session.end(InputOutcome.CANCELLED)),
                        button(Phrases.tr("Type it"), typeIt), confirm)
                .gap(6).align(Node.Align.CENTER);

        Node panel = Node.column(Node.text(Text.of(request.prompt()).build()).wrap(240).textAlign(Node.Align.CENTER),
                        display, Node.row(pad, quick).gap(8), foot)
                .padding(10, 14).gap(7).align(Node.Align.CENTER).background("#17131f").outline("{primary}60", 1)
                .enter(Motion.Tween.of(Motion.Transform.scale(0.9), 5, Motion.Ease.BACK));
        return Node.column(panel).align(Node.Align.CENTER).justify(Node.Justify.CENTER);
    }

    private Node key(ScreenSession screen, String typed, String key) {
        boolean suffix = key.length() == 1 && Character.isLetter(key.charAt(0)) && !key.equals("C");
        Node node = Node.text(Text.of((suffix ? "{secondary_light}" : "{letters}") + key).build())
                .size(24, 18).align(Node.Align.CENTER).justify(Node.Justify.CENTER)
                .background(suffix ? "#2a2240" : "#201a2b").hover("#2e2541").hoverOutline("{primary}")
                .active(null, Motion.Transform.scale(0.92));
        return node.onClick(event -> screen.set(VALUE_KEY, press(typed, key)));
    }

    private static Node step(ScreenSession screen, String label, String result) {
        return Node.text(Text.of("{letters}" + label).build()).scale(0.8).size(40, 18).align(Node.Align.CENTER)
                .justify(Node.Justify.CENTER).background("#201a2b").hover("#2e2541").hoverOutline("{primary}")
                .active(null, Motion.Transform.scale(0.92))
                .onClick(event -> screen.set(VALUE_KEY, result));
    }

    private static Node button(String label, Runnable click) {
        return Node.text(Text.of("{letters}" + label).build()).padding(4, 12).background("{letters_black}20")
                .hover("{primary}50").active(null, Motion.Transform.scale(0.95)).onClick(event -> click.run());
    }

    private boolean signed() {
        return request instanceof NumberInput<?> && (minimum == null || minimum.signum() < 0);
    }

    /** What the typed text becomes after one key. Keys that would make it unreadable do nothing. */
    static String press(String typed, String key) {
        boolean hasSuffix = !typed.isEmpty() && Character.isLetter(typed.charAt(typed.length() - 1));
        return switch (key) {
            case "C" -> "";
            case "⌫" -> typed.isEmpty() ? typed : typed.substring(0, typed.length() - 1);
            case "K", "M", "B" -> {
                String base = hasSuffix ? typed.substring(0, typed.length() - 1) : typed;
                yield base.isEmpty() || base.equals("-") || base.endsWith(".") ? typed : base + key;
            }
            case "." -> hasSuffix || typed.contains(".") || typed.length() >= LONGEST
                    ? typed : (typed.isEmpty() || typed.equals("-") ? typed + "0." : typed + ".");
            default -> hasSuffix || typed.length() >= LONGEST ? typed
                    : typed.equals("0") ? key : typed.equals("-0") ? "-" + key : typed + key;
        };
    }

    /** The typed value changed by a quick step, written short; an unreadable value counts as zero. */
    private static String apply(String typed, java.util.function.UnaryOperator<BigDecimal> change) {
        BigDecimal value = net.exylia.lib.format.Amounts.parseSigned(typed).orElse(BigDecimal.ZERO);
        return compact(change.apply(value));
    }

    /** {@code 1700000} as {@code 1.7M}, kept exact: a value that needs more than two decimals stays in full. */
    static String compact(BigDecimal value) {
        BigDecimal abs = value.abs();
        for (int i = 0; i < UNITS.length; i++) {
            if (abs.compareTo(UNITS[i]) < 0) continue;
            BigDecimal scaled = value.divide(UNITS[i]).stripTrailingZeros();
            if (scaled.scale() <= 2) return scaled.toPlainString() + SUFFIXES[i];
        }
        return value.stripTrailingZeros().toPlainString();
    }

    /** The value read back in full, grouped: {@code 1,700,000}. */
    static String full(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.##########", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return format.format(value);
    }

    private static @Nullable BigDecimal decimal(@Nullable Number number) {
        return number == null ? null : new BigDecimal(number.toString());
    }
}
