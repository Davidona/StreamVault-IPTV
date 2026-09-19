import re
import unittest


from tools.catalog_connected_validation import (
    _focused_target_matches,
    assert_snapshot,
    browse_reorder_contract,
    download_card_is_completed,
    extract_ui_strings,
    parse_bounds,
    reorder_move_action,
    ordered_reorder_markers,
    selected_library_pagination_contract,
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

    def test_download_completion_requires_title_status_and_output_path_in_one_card(self):
        completed_dump = """<hierarchy>
          <node text="Completed" />
          <node clickable="true">
            <node text="Fixture Movie One" />
            <node text="/storage/emulated/0/Android/data/com.streamvault.app.debug/files/Download/StreamVault/Fixture Movie One.mp4" />
            <node text="Completed" />
          </node>
          <node clickable="true">
            <node text="Fixture Movie Two" />
            <node text="/storage/emulated/0/Android/data/com.streamvault.app.debug/files/Download/StreamVault/Fixture Movie Two.mp4" />
          </node>
        </hierarchy>"""
        incomplete_dump = """<hierarchy>
          <node text="Completed" />
          <node clickable="true">
            <node text="Fixture Movie One" />
            <node text="/storage/emulated/0/Android/data/com.streamvault.app.debug/files/Download/StreamVault/Fixture Movie One.mp4" />
          </node>
        </hierarchy>"""

        self.assertTrue(download_card_is_completed(completed_dump, "Fixture Movie One"))
        self.assertFalse(download_card_is_completed(incomplete_dump, "Fixture Movie One"))

    def test_focused_target_matches_walks_to_clickable_parent(self):
        dump = """<hierarchy>
          <node text="" clickable="true" focused="false" bounds="[0,0][300,100]">
            <node text="Save Order" clickable="false" focused="true" bounds="[10,10][200,80]" />
          </node>
        </hierarchy>"""

        self.assertTrue(_focused_target_matches(dump, [re.compile(r"^Save Order$")]))

    def test_series_selected_library_pagination_contract_uses_series_markers(self):
        self.assertEqual(
            {
                "route": "series",
                "entry_marker": "Browse Full Series Library",
                "fixture_marker": "Fixture Series One",
                "loaded_marker": "Pagination Series 63",
                "page_marker": "Load more (60/63)",
            },
            selected_library_pagination_contract("series"),
        )

    def test_browse_reorder_contract_uses_content_specific_markers(self):
        self.assertEqual(
            {
                "route": "movies",
                "category_marker": "★ Favorites",
                "options_marker": "Reorder Items",
                "mode_marker": "Reordering ★ Favorites",
                "first_marker": "Fixture Movie One",
                "second_marker": "Fixture Movie Two",
            },
            browse_reorder_contract("movies"),
        )
        self.assertEqual(
            {
                "route": "series",
                "category_marker": "★ Favorites",
                "options_marker": "Reorder Items",
                "mode_marker": "Reordering ★ Favorites",
                "first_marker": "Fixture Series One",
                "second_marker": "Fixture Series Two",
            },
            browse_reorder_contract("series"),
        )

    def test_reorder_move_action_handles_either_persisted_order(self):
        first = "Fixture Movie One"
        second = "Fixture Movie Two"

        self.assertEqual(
            (second, "KEYCODE_DPAD_UP"),
            reorder_move_action([first, second], [second, first], first, second),
        )
        self.assertEqual(
            (second, "KEYCODE_DPAD_DOWN"),
            reorder_move_action([second, first], [first, second], first, second),
        )

    def test_reorder_marker_order_uses_grid_columns_when_focus_bounds_differ(self):
        dump = """<hierarchy>
          <node content-desc="Fixture Movie Two" clickable="true" focused="true" bounds="[362,467][656,910]">
            <node text="Fixture Movie Two" bounds="[391,852][596,880]" />
          </node>
          <node content-desc="Fixture Movie One" clickable="true" bounds="[68,480][346,897]">
            <node text="Fixture Movie One" bounds="[96,843][289,869]" />
          </node>
        </hierarchy>"""

        self.assertEqual(
            ["Fixture Movie One", "Fixture Movie Two"],
            ordered_reorder_markers(dump, ("Fixture Movie One", "Fixture Movie Two")),
        )


if __name__ == "__main__":
    unittest.main()
