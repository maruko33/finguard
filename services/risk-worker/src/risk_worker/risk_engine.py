from decimal import Decimal

from risk_worker.models import (
    RiskLevel,
    RiskResult,
    TransactionForRisk,
)


MEDIUM_AMOUNT_THRESHOLD = Decimal("1000")
HIGH_AMOUNT_THRESHOLD = Decimal("5000")


def evaluate_risk(transaction: TransactionForRisk) -> RiskResult:
    if transaction.amount >= HIGH_AMOUNT_THRESHOLD:
        return RiskResult(
            transaction_id=transaction.transaction_id,
            risk_score=80,
            risk_level=RiskLevel.HIGH,
            reasons=["HIGH_TRANSACTION_AMOUNT"],
        )

    if transaction.amount >= MEDIUM_AMOUNT_THRESHOLD:
        return RiskResult(
            transaction_id=transaction.transaction_id,
            risk_score=50,
            risk_level=RiskLevel.MEDIUM,
            reasons=["ELEVATED_TRANSACTION_AMOUNT"],
        )

    return RiskResult(
        transaction_id=transaction.transaction_id,
        risk_score=10,
        risk_level=RiskLevel.LOW,
        reasons=[],
    )