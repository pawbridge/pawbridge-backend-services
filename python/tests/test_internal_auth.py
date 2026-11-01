import asyncio
import os
import unittest
from unittest.mock import patch

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient

from app.entrypoints.chatbot_api import app as chatbot_app
from app.entrypoints.photo_api import create_app as create_photo_app
from app.internal_auth import require_internal_key


class InternalAuthTest(unittest.TestCase):
    def test_chatbot_and_photo_refuse_to_start_without_a_key(self):
        # Enter each app's real lifespan; TestClient leaks streams when startup fails.
        async def start(app):
            async with app.router.lifespan_context(app):
                pass
        for app in (chatbot_app, create_photo_app()):
            for key in ("", "  "):
                with self.subTest(app=app.title, key=key), patch.dict(os.environ, {"INTERNAL_API_KEY": key}, clear=True):
                    with self.assertRaisesRegex(RuntimeError, "Internal authentication"):
                        asyncio.run(start(app))
            with patch.dict(os.environ, {"INTERNAL_API_KEY": "test-key"}, clear=True):
                asyncio.run(start(app))

    def test_key_check_statuses(self):
        app = FastAPI()
        app.get("/probe", dependencies=[Depends(require_internal_key)])(lambda: {})
        client = TestClient(app)
        cases = [({"INTERNAL_API_KEY": "  "}, {"X-Internal-Api-Key": "  "}, 503),
                 ({"INTERNAL_API_KEY": "test-key"}, {}, 401),
                 ({"INTERNAL_API_KEY": "test-key"}, {"X-Internal-Api-Key": "test-key2"}, 401),
                 ({"INTERNAL_API_KEY": "test-key"}, {"x-internal-api-key": "test-key"}, 200)]
        for environment, headers, status in cases:
            with self.subTest(headers=headers), patch.dict(os.environ, environment, clear=True):
                self.assertEqual(client.get("/probe", headers=headers).status_code, status)


if __name__ == "__main__":
    unittest.main()
