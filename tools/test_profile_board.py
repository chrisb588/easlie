import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("profile_board", Path(__file__).with_name("profile_board.py"))
profile = importlib.util.module_from_spec(spec)
spec.loader.exec_module(profile)


class ProfileCaptureTest(unittest.TestCase):
    def test_percentiles_interpolate_small_samples(self):
        self.assertIsNone(profile.percentile([], .95))
        self.assertEqual(8, profile.percentile([4, 12], .5))
        self.assertAlmostEqual(11.6, profile.percentile([4, 12], .95))

    def test_frames_exclude_flagged_incomplete_and_malformed_rows(self):
        output = """Window: board
Flags,IntendedVsync,FrameCompleted,
0,1000000,19000000,
1,2000000,29000000,
0,3000000,9223372036854775807,
0,4000000,
"""
        self.assertEqual([("board", 1000000, 18.0)], list(profile.frame_rows(output)))

    def test_future_completion_is_excluded_but_a_real_frozen_frame_is_kept(self):
        output = """Window: board
Flags,IntendedVsync,FrameCompleted,
0,1000000,801000000,
0,2000000,6701230487948702906,
"""
        self.assertEqual([("board", 1000000, 800.0)], list(profile.frame_rows(output, 1000000000)))

    def test_summary_uses_device_frame_time_and_deduplicates_polling(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "profile.log").write_text("""--------- beginning of main
100.0 I EaslieBoardProfile: stage=pan repeat=1 event=start time_ns=1000000
         100.1 D EaslieImageProfile: refresh_hits=10 refresh_misses=3 decodes_scheduled=2 cache_bytes=100 budget_bytes=1000
100.2 D EaslieImageProfile: decode_ms=4 sample=2 bytes=100 source_edge=6000
101.0 D EaslieImageProfile: refresh_hits=20 refresh_misses=4 decodes_scheduled=3 cache_bytes=200 budget_bytes=1000
102.0 I EaslieBoardProfile: stage=pan repeat=1 event=end time_ns=40000000
102.1 I EaslieBoardProfile: board_restored=true event=complete
""")
            # First-observed wall time is outside the stage, while the frame's device time is inside.
            (directory / "frames.txt").write_text("Window: board\nFlags,FrameTimelineVsyncId,IntendedVsync,FrameCompleted,\n0,123,2000000,20000000,\n0,124,3000000,6701230487948702906,\n")
            (directory / "memory.txt").write_text("TOTAL PSS: 1234\nJava Heap: 100\nNative Heap: 200\nGraphics: 300\n")
            samples = [dict(timestamp=t, frames="frames.txt", memory="memory.txt") for t in (101, 103)]
            (directory / "samples.jsonl").write_text("\n".join(json.dumps(s) for s in samples))
            result = profile.summarize(directory)
            stage = result["stages"][0]
            self.assertEqual(1, stage["frames"]["count"])
            self.assertEqual(1, stage["frames"]["over_16_67_ms"])
            self.assertEqual(1, stage["frames"]["invalid_future_completions"])
            self.assertEqual(0, stage["frames"]["frozen_over_700_ms"])
            self.assertEqual(1234, stage["peak_sample"]["TOTAL PSS"])
            self.assertEqual(10, stage["cache"]["refresh_hits"])
            self.assertEqual(1, stage["cache"]["decodes_scheduled"])
            self.assertEqual(4, stage["decode"]["p95_ms"])
            self.assertEqual(1, stage["large_source_decode"]["count"])
            self.assertEqual({"2": 1}, stage["large_source_decode"]["sample_factors"])
            self.assertTrue(result["board_restored"])


if __name__ == "__main__":
    unittest.main()
