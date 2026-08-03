from ingest.config import admin_pipeline, partner_pipeline


def _ingest(value, callback):
    return {"external_id": callback(value)}


def ingest_partner(value):
    return _ingest(value, partner_pipeline())


def ingest_admin(value):
    return _ingest(value, admin_pipeline())
