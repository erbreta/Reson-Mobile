import json
import unittest
from pathlib import Path

CATALOGUE = Path(__file__).resolve().parents[1] / 'app/src/main/assets/ipa_catalogue.json'

class BundledCatalogueTest(unittest.TestCase):
    def test_keyboard_metadata_and_scientific_pair_order(self):
        data = json.loads(CATALOGUE.read_text(encoding='utf-8'))
        self.assertEqual({'consonant_pulmonic', 'vowel', 'diacritic', 'suprasegmental',
                          'tone', 'consonant_non_pulmonic', 'consonant_other'},
                         {s['category'] for s in data['symbols']})
        by_id = {s['id']: s for s in data['symbols']}
        for s in data['symbols']:
            if s['category'] == 'consonant_pulmonic':
                self.assertTrue(s['manner'])
            if s['category'] == 'vowel':
                self.assertTrue(s['backness'])
        for name, category, row_feature, column_feature, pair_feature, order in (
            ('pulmonic', 'consonant_pulmonic', 'manner', 'place', 'voicing', ('voiceless', 'voiced')),
            ('vowels', 'vowel', 'height', 'backness', 'rounding', ('unrounded', 'rounded')),
        ):
            reached = set()
            for cell in data[name]['cells']:
                for index, symbol_id in enumerate(cell['symbols']):
                    if symbol_id is None:
                        continue
                    symbol = by_id[symbol_id]
                    reached.add(symbol_id)
                    self.assertEqual(category, symbol['category'])
                    self.assertEqual(cell['row'], symbol[row_feature])
                    self.assertEqual(cell['column'], symbol[column_feature])
                    self.assertEqual(order[index], symbol[pair_feature])
            self.assertEqual({s['id'] for s in data['symbols'] if s['category'] == category}, reached)

    def test_complete_catalogue_schema_and_charts(self):
        data = json.loads(CATALOGUE.read_text(encoding='utf-8'))
        self.assertEqual(1, data['schema'])
        self.assertEqual(173, len(data['symbols']))
        ids = set()
        for s in data['symbols']:
            for key in ('id', 'symbol', 'name', 'category', 'subcategory', 'description'):
                self.assertIsInstance(s[key], str)
            for key in ('place', 'manner', 'voicing', 'height', 'backness', 'rounding', 'example'):
                self.assertTrue(s[key] is None or isinstance(s[key], str), (s['id'], key))
            for key in ('aliases', 'codepoints'):
                self.assertIsInstance(s[key], list, (s['id'], key))
                self.assertTrue(all(isinstance(v, str) for v in s[key]))
            for key in ('is_combining', 'is_modifier'):
                self.assertIsInstance(s[key], bool)
            self.assertNotIn(s['id'], ids)
            ids.add(s['id'])
        for name in ('pulmonic', 'vowels'):
            chart = data[name]
            for axis in ('rows', 'columns'):
                self.assertTrue(all(isinstance(pair, list) and len(pair) == 2 and all(isinstance(x, str) for x in pair) for pair in chart[axis]))
            rows = {x[0] for x in chart['rows']}
            columns = {x[0] for x in chart['columns']}
            for cell in chart['cells']:
                self.assertIn(cell['row'], rows)
                self.assertIn(cell['column'], columns)
                self.assertIsInstance(cell['impossible'], bool)
                self.assertEqual(2, len(cell['symbols']))
                self.assertTrue(all(x is None or x in ids for x in cell['symbols']))

if __name__ == '__main__':
    unittest.main()
