import json
import logging
import os

from decimal import Decimal
from uuid import UUID

import boto3

from risk_worker.models import TransactionForRisk
from risk_worker.risk_engine import evaluate_risk


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)

logger = logging.getLogger(__name__)


def parse_transaction(message_body: str) -> TransactionForRisk:
    payload = json.loads(message_body, parse_float=Decimal)

    return TransactionForRisk(
        transaction_id=UUID(payload["transactionId"]),
        amount=payload["amount"],
        currency=payload["currency"],
    )


def main():
    queue_url = os.environ["SQS_QUEUE_URL"]

    sqs = boto3.client("sqs")

    logger.info("Risk worker started")

    while True:
        response = sqs.receive_message(
            QueueUrl=queue_url,
            MaxNumberOfMessages=1,
            WaitTimeSeconds=20,
        )

        messages = response.get("Messages", [])

        if not messages:
            continue

        for message in messages:
            try:
                transaction = parse_transaction(message["Body"])

                result = evaluate_risk(transaction)

                logger.info(
                    "Risk evaluated transaction=%s score=%s level=%s reasons=%s",
                    result.transaction_id,
                    result.risk_score,
                    result.risk_level.value,
                    result.reasons,
                )

                sqs.delete_message(
                    QueueUrl=queue_url,
                    ReceiptHandle=message["ReceiptHandle"],
                )

                logger.info(
                    "Message deleted transaction=%s",
                    result.transaction_id,
                )

            except Exception:
                logger.exception("Failed to process SQS message")

                # IMPORTANT:
                # Do NOT delete the message.
                # SQS will make it visible again after the visibility timeout.


if __name__ == "__main__":
    main()