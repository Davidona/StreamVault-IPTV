import unittest


from tools.catalog_connected_validation import (
    assert_snapshot,
    extract_ui_strings,
    parse_bounds,
)


class CatalogConnectedValidationTest(unittest.TestCase):
    def test_extracts_text_and_content_descriptions_from_ui_dump(self):
        dump = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
  <node text="Fixture Movie One" content-desc="Movie One poster" clickable="true" bounds="[10,20][210,420]" />
  <node text="" content-desc="streamvault.destination:movies" clickable="false" bounds="[0,0][1920,1080]" />
</hierarchy>"""

        self.assertEqual(
            extract_ui_strings(dump),
            {"Fixture Movie One", "Movie One poster", "streamvault.destination:movies"},
        )
        self.assertEqual(parse_bounds("[10,20][210,420]"), (10, 20, 210, 420))

    def test_assert_snapshot_reports_missing_and_forbidden_markers(self):
        dump = """<hierarchy><node text="Fixture Movie One" /><node text="Sync needed" /></hierarchy>"""

        assert_snapshot(dump, required=("Fixture Movie One",))
        with self.assertRaisesRegex(AssertionError, "missing marker 'Fixture Series One'"):
            assert_snapshot(dump, required=("Fixture Series One",))
        with self.assertRaisesRegex(AssertionError, "forbidden marker 'Sync needed'"):
            assert_snapshot(dump, forbidden=("Sync needed",))


if __name__ == "__main__":
    unittest.main()
