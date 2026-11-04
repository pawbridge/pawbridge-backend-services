import io
import os
import sys
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
from PIL import Image
from app.gallery import storage as lost_storage
from app.lost_search.service import search_photo
from app.gallery.pg_store import PostgresqlGalleryStore


class LostStorageTest(unittest.TestCase):
    def test_default_storage_selects_postgresql_without_creating_a_pool(self):
        driver=Mock()
        with patch.dict(os.environ,{},clear=True), patch.dict(sys.modules,{'psycopg_pool':driver}):
            self.assertEqual(lost_storage.backend(),'postgresql')
            driver.ConnectionPool.assert_not_called()
        self.assertIsNone(lost_storage._store)

    def test_default_storage_session_yields_the_postgresql_store(self):
        store=Mock()
        with patch.dict(os.environ,{},clear=True), \
                patch.object(lost_storage,'get_postgresql_store',return_value=store) as factory, \
                patch.object(lost_storage,'close_postgresql_store') as close:
            with lost_storage.storage_session() as session:
                self.assertIs(session,store)
            factory.assert_called_once_with()
            close.assert_called_once_with()

    def test_unknown_or_removed_backend_fails_closed_instead_of_falling_back(self):
        driver=Mock()
        for value in ('misspelled','elasticsearch'):
            with self.subTest(backend=value), patch.dict(os.environ,{'LOST_STORAGE_BACKEND':value}), \
                    patch.dict(sys.modules,{'psycopg_pool':driver}):
                with self.assertRaises(RuntimeError):
                    with lost_storage.storage_session(): self.fail('Cannot serve an unsupported backend')
                driver.ConnectionPool.assert_not_called()
                self.assertIsNone(lost_storage._store)

    def test_startup_failure_closes_created_pool(self):
        store=Mock()
        with patch.dict(os.environ,{'LOST_STORAGE_BACKEND':'postgresql'}), \
                patch.object(lost_storage,'get_postgresql_store',return_value=store), \
                patch.object(lost_storage,'_store',store):
            with self.assertRaises(RuntimeError):
                with lost_storage.storage_session(): raise RuntimeError('startup failed')
            store.pool.close.assert_called_once()
            self.assertIsNone(lost_storage._store)

    def test_postgresql_retrieval_keeps_auxiliary_ranking_and_resolved_option(self):
        image=io.BytesIO(); Image.new('RGB',(8,8),'white').save(image,format='PNG')
        calls=[]
        def encode(*args):
            calls.append('inference')
            return SimpleNamespace(vector=[1.]+[0.]*1023,animal_vector=None,model_version='test',focus_status='test',coat_color=None)
        def retrieve(*args):
            self.assertEqual(calls,['inference'])
            self.assertEqual(args[3],('NOTICE','PROTECT','ADOPTED','RETURNED','DONATED','RELEASED'))
            self.assertEqual(args[4],200)
            return [{'_score':1.8,'_source':{'id':1}},
                    {'_score':1.795,'_source':{'id':2,'happen_place':'서울'}}]
        store=Mock();store.search.side_effect=retrieve
        with patch.dict(os.environ,{'LOST_STORAGE_BACKEND':'postgresql','LOST_SEARCH_COAT_COLOR_WEIGHT':'0'}), \
                patch('app.vision.dinov3.get_encoder',return_value=SimpleNamespace(encode_with_metadata=encode)), \
                patch('app.vision.dinov3.gallery_index',return_value='test'), \
                patch.object(lost_storage,'get_postgresql_store',return_value=store):
            result=search_photo(image.getvalue(),'DOG',region='서울',include_adopted_or_returned=True)
        self.assertEqual([row['animalId'] for row in result['candidates']],[2,1])

    def test_corrupt_vector_cannot_reach_database(self):
        pool=Mock();store=PostgresqlGalleryStore(pool)
        for vector in ([0.]*1024,[1.]*384,[float('nan')]*1024):
            with self.subTest(length=len(vector)), self.assertRaises(RuntimeError):
                store.search('test',SimpleNamespace(vector=vector,animal_vector=None),'DOG',('PROTECT',))
        pool.connection.assert_not_called()
