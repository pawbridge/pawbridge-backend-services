import asyncio
import os
import unittest
from unittest.mock import AsyncMock, patch

from app.settings import CoatColorSettings, GallerySyncSettings, PostgresSettings, _Settings

INDEX = "animals-lost-dinov3-sam3-eval-v1"


class SettingsTest(unittest.TestCase):
    def test_errors_name_the_variable_but_never_echo_its_value(self):
        environment = {"LOST_PG_DSN": "postgresql://user:secret-password@db/pawbridge", "LOST_PG_POOL_MAX_SIZE": "9"}
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaises(RuntimeError) as raised:
                PostgresSettings.load()
        self.assertIn("LOST_PG_POOL_MAX_SIZE", str(raised.exception))
        self.assertNotIn("secret-password", str(raised.exception))
        # The pydantic error holds the input values; it must not be chained into tracebacks.
        self.assertIsNone(raised.exception.__cause__)
        self.assertTrue(raised.exception.__suppress_context__)

    def test_a_model_receives_only_its_own_variables(self):
        environment = {"GEMINI_API_KEY": "unrelated-secret", "LOST_GALLERY_SYNC_ENABLED": "true",
                       "LOST_GALLERY_SOURCE_KEY": "feed-secret"}
        with patch.dict(os.environ, environment, clear=True):
            with self.assertRaises(RuntimeError) as raised:
                GallerySyncSettings.load()
        # Suppressed, but still reachable as __context__ from a debugger or a careless handler.
        validation_error = raised.exception.__context__
        self.assertNotIn("unrelated-secret", repr(validation_error.errors()))
        self.assertNotIn("feed-secret", str(validation_error))

    def test_every_setting_names_its_environment_variable(self):
        # load() reads only aliases, so a field without one would silently keep its default.
        for model in _Settings.__subclasses__():
            for name, field in model.model_fields.items():
                self.assertTrue(field.alias, f"{model.__name__}.{name}")

    def test_leftover_elasticsearch_backend_fails_instead_of_being_ignored(self):
        with patch.dict(os.environ, {"LOST_STORAGE_BACKEND": "elasticsearch"}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "LOST_STORAGE_BACKEND"):
                PostgresSettings.load()

    def test_enabled_gallery_refresh_lists_every_missing_source_setting(self):
        with patch.dict(os.environ, {"LOST_GALLERY_SYNC_ENABLED": "true", "LOST_GALLERY_SOURCE_URL": "http://feed"}, clear=True):
            with self.assertRaises(RuntimeError) as raised:
                GallerySyncSettings.load()
        message = str(raised.exception)
        for name in ("LOST_GALLERY_SOURCE_KEY", "LOST_GALLERY_R2_HOST", "LOST_GALLERY_PHOTO_ROOT"):
            self.assertIn(name, message)
        self.assertNotIn("LOST_GALLERY_SOURCE_URL", message)
        with patch.dict(os.environ, {"LOST_GALLERY_SYNC_ENABLED": "typo"}, clear=True):
            with self.assertRaisesRegex(RuntimeError, "LOST_GALLERY_SYNC_ENABLED"):
                GallerySyncSettings.load()
        with patch.dict(os.environ, {}, clear=True):
            self.assertFalse(GallerySyncSettings.load().enabled)

    def test_settings_follow_the_current_environment(self):
        with patch.dict(os.environ, {"LOST_SEARCH_COAT_COLOR_WEIGHT": ".1"}):
            self.assertEqual(CoatColorSettings.load().ranking_weight, .1)
        with patch.dict(os.environ, {"LOST_SEARCH_COAT_COLOR_WEIGHT": "0"}):
            self.assertEqual(CoatColorSettings.load().ranking_weight, 0)

    def test_vision_startup_refuses_a_missing_color_weight_before_loading_models(self):
        from app.entrypoints.vision_api import lifespan
        async def enter():
            async with lifespan(None):
                self.fail("A missing color weight must stop startup")
        environment = {"INTERNAL_API_KEY": "test-key", "LOST_SEARCH_VISUAL_PROFILE": "sam3-animal-focus",
                       "LOST_SEARCH_INDEX": INDEX}
        with patch.dict(os.environ, environment, clear=True), \
                patch("app.entrypoints.vision_api.anyio.to_thread.run_sync", new_callable=AsyncMock) as run:
            with self.assertRaisesRegex(RuntimeError, "LOST_SEARCH_COAT_COLOR_WEIGHT"):
                asyncio.run(enter())
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
