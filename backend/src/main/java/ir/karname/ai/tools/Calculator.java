package ir.karname.ai.tools;

import ir.karname.common.persian.PersianText;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Exact arithmetic for the assistant, so it never adds or divides in its head: numbers (Persian
 * or Latin digits, with thousands separators), + − × ÷ ^ (whole exponents), percentages and
 * parentheses, evaluated in decimal.
 */
public final class Calculator {

    static final int MAX_LENGTH = 300;
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final int MAX_DEPTH = 40;
    /** Far beyond any useful answer, yet rounding it for the result stays cheap. */
    private static final int MAX_SCALE = 1000;

    private final String text;
    private int pos;
    private int depth;

    private Calculator(String text) {
        this.text = text;
    }

    public static BigDecimal evaluate(String expression) {
        if (expression == null || expression.isBlank() || expression.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("expression must be 1 to " + MAX_LENGTH + " characters");
        }
        String normalized = PersianText.normalizeDigits(expression)
                .replace('×', '*').replace('÷', '/').replace('−', '-').replace('٫', '.').replace('٪', '%');
        Calculator calculator = new Calculator(normalized);
        BigDecimal result = calculator.expression();
        calculator.skipSpaces();
        if (calculator.pos < calculator.text.length()) {
            throw new IllegalArgumentException("unexpected '" + calculator.text.charAt(calculator.pos) + "' at position " + (calculator.pos + 1));
        }
        BigDecimal rounded = result.setScale(Math.min(Math.max(result.scale(), 0), 8), RoundingMode.HALF_EVEN).stripTrailingZeros();
        return rounded.scale() < 0 ? rounded.setScale(0) : rounded;
    }

    /**
     * Precision is capped by {@code MC}, but the scale is not: powers of powers of a small number
     * reach "1E-1000000000", and rounding that for the answer would build a billion-digit number.
     */
    private static BigDecimal checked(BigDecimal value) {
        if (Math.abs((long) value.scale()) > MAX_SCALE) {
            throw new IllegalArgumentException("a result is too large or too small to compute");
        }
        return value;
    }

    private BigDecimal expression() {
        enter();
        BigDecimal value = term();
        while (true) {
            if (accept('+')) {
                value = checked(value.add(term(), MC));
            } else if (accept('-')) {
                value = checked(value.subtract(term(), MC));
            } else {
                depth--;
                return value;
            }
        }
    }

    private BigDecimal term() {
        BigDecimal value = power();
        while (true) {
            if (accept('*')) {
                value = checked(value.multiply(power(), MC));
            } else if (accept('/')) {
                BigDecimal divisor = power();
                if (divisor.signum() == 0) {
                    throw new IllegalArgumentException("division by zero");
                }
                value = checked(value.divide(divisor, MC));
            } else {
                return value;
            }
        }
    }

    private BigDecimal power() {
        BigDecimal base = unary();
        if (accept('^')) {
            enter();
            BigDecimal exponent = power();
            depth--;
            if (exponent.stripTrailingZeros().scale() > 0 || exponent.abs().compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw new IllegalArgumentException("exponents must be whole numbers up to 1000");
            }
            int n = exponent.intValueExact();
            if (n < 0 && base.signum() == 0) {
                throw new IllegalArgumentException("division by zero");
            }
            return checked(n >= 0 ? base.pow(n, MC) : BigDecimal.ONE.divide(checked(base.pow(-n, MC)), MC));
        }
        return base;
    }

    private BigDecimal unary() {
        if (accept('-')) {
            enter();
            BigDecimal v = unary().negate();
            depth--;
            return v;
        }
        if (accept('+')) {
            return unary();
        }
        BigDecimal value = primary();
        while (accept('%')) {
            value = checked(value.movePointLeft(2));
        }
        return value;
    }

    private BigDecimal primary() {
        if (accept('(')) {
            BigDecimal value = expression();
            if (!accept(')')) {
                throw new IllegalArgumentException("missing ')'");
            }
            return value;
        }
        skipSpaces();
        int start = pos;
        StringBuilder digits = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isDigit(c) || c == '.') {
                digits.append(c);
            } else if ((c == ',' || c == '٬') && pos + 1 < text.length() && Character.isDigit(text.charAt(pos + 1)) && !digits.isEmpty()) {
                // a thousands separator between digits
            } else {
                break;
            }
            pos++;
        }
        if (digits.isEmpty()) {
            throw new IllegalArgumentException(pos < text.length() ? "unexpected '" + text.charAt(pos) + "' at position " + (pos + 1)
                    : "expression ends early");
        }
        try {
            return new BigDecimal(digits.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid number '" + text.substring(start, pos).strip() + "'");
        }
    }

    private boolean accept(char c) {
        skipSpaces();
        if (pos < text.length() && text.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void skipSpaces() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new IllegalArgumentException("expression nested too deeply");
        }
    }
}
