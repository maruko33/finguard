from decimal import Decimal
from uuid import uuid4

from risk_worker.models import RiskLevel, TransactionForRisk
from risk_worker.risk_engine import evaluate_risk


def test_low_risk_transaction():
    transaction = TransactionForRisk(
        transaction_id=uuid4(),
        amount=Decimal("100.00"),
        currency="CAD",
    )

    result = evaluate_risk(transaction)

    assert result.risk_level == RiskLevel.LOW
    assert result.risk_score == 10


def test_medium_risk_transaction():
    transaction = TransactionForRisk(
        transaction_id=uuid4(),
        amount=Decimal("1500.00"),
        currency="CAD",
    )

    result = evaluate_risk(transaction)

    assert result.risk_level == RiskLevel.MEDIUM
    assert result.risk_score == 50


def test_high_risk_transaction():
    transaction = TransactionForRisk(
        transaction_id=uuid4(),
        amount=Decimal("6000.00"),
        currency="CAD",
    )

    result = evaluate_risk(transaction)

    assert result.risk_level == RiskLevel.HIGH
    assert result.risk_score == 80
    assert "HIGH_TRANSACTION_AMOUNT" in result.reasons