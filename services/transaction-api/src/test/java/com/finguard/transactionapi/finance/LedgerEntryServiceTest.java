package com.finguard.transactionapi.finance;

import com.finguard.transactionapi.finance.dto.CreateFinanceEntryRequest;
import com.finguard.transactionapi.finance.domain.EntryType;
import com.finguard.transactionapi.finance.exception.*;
import com.finguard.transactionapi.finance.repository.*;
import com.finguard.transactionapi.finance.service.LedgerEntryService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LedgerEntryServiceTest {
    final LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
    final FinancialAccountRepository accounts = mock(FinancialAccountRepository.class);
    final CategoryRepository categories = mock(CategoryRepository.class);
    final LedgerEntryService service = new LedgerEntryService(entries,accounts,categories);

    @Test void rejectsMissingRequiredFieldsBeforeRepositoryAccess() {
        var user=UUID.randomUUID();
        assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(user,null));
        var request=new CreateFinanceEntryRequest(UUID.randomUUID(),null,EntryType.EXPENSE,
                BigDecimal.ONE,"CAD",null,null,"");
        assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(user,request));
        assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(null,request));
        verifyNoInteractions(entries,accounts,categories);
    }
    @Test void rejectsInvalidMoneyBeforeRepositoryAccess() {
        for (var amount : new String[]{"0","-1","1.001","100000000000000000"}) {
            var request=new CreateFinanceEntryRequest(UUID.randomUUID(),null,EntryType.EXPENSE,
                    new BigDecimal(amount),"CAD",LocalDate.now(),null,"");
            assertThrows(InvalidFinanceEntryException.class,()->service.createEntry(UUID.randomUUID(),request));
        }
        verifyNoInteractions(entries,accounts,categories);
    }
    @Test void lookupUsesOwnerAndSoftDeletePredicateOnly() {
        var user=UUID.randomUUID();
        var id=UUID.randomUUID();
        assertThrows(FinanceEntryNotFoundException.class,()->service.findById(user,id));
        verify(entries).findByIdAndUserIdAndDeletedAtIsNull(id,user);
        verifyNoMoreInteractions(entries);
        verifyNoInteractions(accounts,categories);
    }
}
