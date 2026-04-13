"""
TRACE Shield — Stripe Billing Integration
Handles subscriptions for Shield, Shield Pro, Shield Business, Shield Enterprise.

⚠️  STRIPE LIVE MODE DISABLED by default.
    Set TRACE_STRIPE_ENABLED=true + STRIPE_SECRET_KEY to activate.
    Requires George's explicit approval before charging customers.
"""
import os
from typing import Optional

STRIPE_ENABLED = os.environ.get("TRACE_STRIPE_ENABLED", "false").lower() == "true"
STRIPE_SECRET_KEY = os.environ.get("STRIPE_SECRET_KEY", "")
STRIPE_WEBHOOK_SECRET = os.environ.get("STRIPE_WEBHOOK_SECRET", "")

PLAN_PRICES = {
    "shield":          {"name": "Shield",          "price": 2999,  "price_display": "$29.99/mo",  "verifications": 50},
    "shield_pro":      {"name": "Shield Pro",       "price": 9900,  "price_display": "$99/mo",     "verifications": -1},  # -1 = unlimited
    "shield_business": {"name": "Shield Business",  "price": 29900, "price_display": "$299/mo",    "verifications": -1},
    "shield_enterprise":{"name":"Shield Enterprise","price": 49900, "price_display": "$499/mo",    "verifications": -1},
}

# Stripe Price IDs (set these after creating products in Stripe dashboard)
STRIPE_PRICE_IDS = {
    "shield":           os.environ.get("STRIPE_PRICE_SHIELD", ""),
    "shield_pro":       os.environ.get("STRIPE_PRICE_SHIELD_PRO", ""),
    "shield_business":  os.environ.get("STRIPE_PRICE_SHIELD_BUSINESS", ""),
    "shield_enterprise":os.environ.get("STRIPE_PRICE_SHIELD_ENTERPRISE", ""),
}


def create_checkout_session(creator_id: str, email: str, plan: str, success_url: str, cancel_url: str) -> dict:
    """Create a Stripe checkout session for subscription."""
    if not STRIPE_ENABLED:
        return {
            "enabled": False,
            "message": "Stripe billing not yet activated. Contact BITS to complete payment setup.",
            "plan": plan,
            "price_display": PLAN_PRICES.get(plan, {}).get("price_display", "—"),
        }

    try:
        import stripe
        stripe.api_key = STRIPE_SECRET_KEY
        session = stripe.checkout.Session.create(
            mode="subscription",
            customer_email=email,
            line_items=[{"price": STRIPE_PRICE_IDS[plan], "quantity": 1}],
            success_url=success_url + "?session_id={CHECKOUT_SESSION_ID}",
            cancel_url=cancel_url,
            metadata={"creator_id": creator_id, "plan": plan},
        )
        return {"enabled": True, "checkout_url": session.url, "session_id": session.id}
    except Exception as e:
        return {"enabled": False, "error": str(e)}


def cancel_subscription(subscription_id: str) -> dict:
    """Cancel a Stripe subscription."""
    if not STRIPE_ENABLED:
        return {"enabled": False}
    try:
        import stripe
        stripe.api_key = STRIPE_SECRET_KEY
        result = stripe.Subscription.cancel(subscription_id)
        return {"cancelled": True, "status": result.status}
    except Exception as e:
        return {"cancelled": False, "error": str(e)}


def get_plan_info(plan: str) -> dict:
    return PLAN_PRICES.get(plan, PLAN_PRICES["shield"])


def verifications_remaining(plan: str, used: int) -> Optional[int]:
    """Returns None for unlimited, int for limited plans."""
    limit = PLAN_PRICES.get(plan, {}).get("verifications", 50)
    if limit == -1:
        return None  # unlimited
    return max(0, limit - used)
