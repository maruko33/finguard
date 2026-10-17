package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.domain.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {
    @Test void preservesExactCentsAndRejectsLossyOrInvalidAmounts() {
        assertEquals(new BigDecimal("12.30"),new Money(new BigDecimal("12.3"),"CAD").amount());
        assertThrows(ArithmeticException.class,()->new Money(new BigDecimal("1.001"),"CAD"));
        for (String amount : new String[]{"0","-1","100000000000000000"}) {
            assertThrows(IllegalArgumentException.class,()->new Money(new BigDecimal(amount),"CAD"));
        }
        assertThrows(IllegalArgumentException.class,()->new Money(BigDecimal.ONE,"cad"));
    }
}
