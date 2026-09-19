import json
import sys

from decimal import Decimal
from uuid import UUID

from risk_worker.models import TransactionForRisk
from risk_worker.risk_engine import evaluate_risk


def main():
    payload = json.load(sys.stdin, parse_float=Decimal)

    transaction = TransactionForRisk(
        transaction_id=UUID(payload["transactionId"]),
        amount=payload["amount"],
        currency=payload["currency"],
    )

    result = evaluate_risk(transaction)

    print(
        json.dumps(
            {
                "transactionId": str(result.transaction_id),
                "riskScore": result.risk_score,
                "riskLevel": result.risk_level.value,
                "reasons": result.reasons,
            }
        )
    )


if __name__ == "__main__":
    main()