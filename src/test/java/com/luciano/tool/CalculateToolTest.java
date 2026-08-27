package com.luciano.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculateToolTest {

    @Test
    void basicArithmetic() {
        assertEquals(60, new CalculateTool.ExprParser("(12+3)*4").parse());
        assertEquals(1024, new CalculateTool.ExprParser("2^10").parse());
        assertEquals(1, new CalculateTool.ExprParser("5%2").parse());
    }

    @Test
    void powerIsRightAssociative() {
        assertEquals(512, new CalculateTool.ExprParser("2^3^2").parse());
    }

    @Test
    void decimalDivision() {
        assertEquals(2.5, new CalculateTool.ExprParser("10/4").parse(), 0.0001);
    }

    @Test
    void unaryMinus() {
        assertEquals(-15, new CalculateTool.ExprParser("-(3+12)").parse());
    }

    @Test
    void divideByZeroReturnsInfinityNotThrow() {
        double result = new CalculateTool.ExprParser("1/0").parse();
        assertTrue(Double.isInfinite(result));
    }

    @Test
    void malformedExpressionThrows() {
        assertThrows(Exception.class, () -> new CalculateTool.ExprParser("(1+2").parse());
        assertThrows(Exception.class, () -> new CalculateTool.ExprParser("1+").parse());
        assertThrows(Exception.class, () -> new CalculateTool.ExprParser("abc").parse());
    }
}
