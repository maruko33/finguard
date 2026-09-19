from dataclasses import dataclass
from decimal import Decimal
from enum import Enum
from uuid import UUID


class RiskLevel(Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


@dataclass(frozen=True)
class TransactionForRisk:
    transaction_id: UUID
    amount: Decimal
    currency: str


@dataclass(frozen=True)
class RiskResult:
    transaction_id: UUID
    risk_score: int
    risk_level: RiskLevel
    reasons: list[str]