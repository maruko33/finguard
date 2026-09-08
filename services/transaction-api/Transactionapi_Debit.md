TransactionStatus.java
- Could add description to enum values

Transaction.java
- Could convert currency to enum / Currency type
- Add getters when needed
- Avoid unnecessary setters; prefer domain methods
- Add JPA annotations when PostgreSQL is introduced

CreateTransactionRequest.java
- Add Bean Validation annotations
- Could convert currency to enum / Currency type
- Could validate monetary precision

TransactionService.java
- findIdBy(): could throw exception when there is empty return here

Docker will respond for Maven build
- Use multi-stage build
