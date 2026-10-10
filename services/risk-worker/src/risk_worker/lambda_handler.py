import json
import logging

from decimal import Decimal
from uuid import UUID

from risk_worker.models import TransactionForRisk
from risk_worker.risk_engine import evaluate_risk


logger = logging.getLogger(__name__)
logger.setLevel(logging.INFO)


def lambda_handler(event, context):
    for record in event["Records"]:

        # Step 1: Extract JSON from the SQS message
        body = json.loads(record["body"])

        # Step 2: Convert JSON into our domain model
        transaction = TransactionForRisk(
            transaction_id=UUID(body["transactionId"]),
            amount=Decimal(str(body["amount"])),
            currency=body["currency"],
        )

        # Step 3: Reuse the existing risk engine
        result = evaluate_risk(transaction)

        # Step 4: Log the evaluation result
        logger.info(
            "Risk evaluated transaction=%s result=%s",
            transaction.transaction_id,
            result,
        )
