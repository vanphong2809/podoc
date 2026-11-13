import sys
import unittest
from pathlib import Path

# Add backend directory to sys.path
backend_dir = Path(__file__).resolve().parent.parent
if str(backend_dir) not in sys.path:
    sys.path.insert(0, str(backend_dir))

from app.services.audio_extractor import (
    AudioExtractionError,
    CODEC_TO_EXT,
    EXT_TO_MIME,
    format_seconds_to_mmss,
    get_channels_display,
    safe_audio_filename,
)
from app.schemas.audio import AudioProbeResponse, AudioExtractResultResponse


class TestAudioExtractor(unittest.TestCase):
    def test_format_seconds_to_mmss(self):
        self.assertIsNone(format_seconds_to_mmss(None))
        self.assertIsNone(format_seconds_to_mmss(0))
        self.assertEqual(format_seconds_to_mmss(45.2), "00:45")
        self.assertEqual(format_seconds_to_mmss(125.7), "02:06")
        self.assertEqual(format_seconds_to_mmss(3665), "01:01:05")

    def test_get_channels_display(self):
        self.assertIsNone(get_channels_display(None))
        self.assertEqual(get_channels_display(1), "Mono (1 kênh)")
        self.assertEqual(get_channels_display(2), "Stereo (2 kênh)")
        self.assertEqual(get_channels_display(6), "5.1 Surround")

    def test_safe_audio_filename(self):
        self.assertEqual(safe_audio_filename("My Favorite Song / Part 1", "m4a"), "My_Favorite_Song_Part_1.m4a")
        self.assertEqual(safe_audio_filename("Song.mp3", "mp3"), "Song.mp3")
        self.assertEqual(safe_audio_filename("", "opus"), "audio.opus")
        self.assertEqual(safe_audio_filename('Test "Audio": Special', "wav"), "Test_Audio_Special.wav")

    def test_codec_mapping(self):
        self.assertEqual(CODEC_TO_EXT["aac"], "m4a")
        self.assertEqual(CODEC_TO_EXT["mp3"], "mp3")
        self.assertEqual(CODEC_TO_EXT["opus"], "opus")
        self.assertEqual(CODEC_TO_EXT["flac"], "flac")
        self.assertEqual(EXT_TO_MIME["m4a"], "audio/mp4")
        self.assertEqual(EXT_TO_MIME["mp3"], "audio/mpeg")

    def test_audio_probe_response_schema(self):
        probe = AudioProbeResponse(
            has_audio=True,
            codec_name="aac",
            codec_display="AAC (Chất lượng cao)",
            channels=2,
            channels_display="Stereo (2 kênh)",
            sample_rate_hz=48000,
            bit_rate_kbps=320,
            duration_seconds=180.5,
            duration_formatted="03:01",
            suggested_ext="m4a",
            suggested_mime="audio/mp4",
            lossless_possible=True,
        )
        self.assertTrue(probe.has_audio)
        self.assertEqual(probe.suggested_ext, "m4a")
        self.assertEqual(probe.bit_rate_kbps, 320)

    def test_audio_extract_result_schema(self):
        res = AudioExtractResultResponse(
            success=True,
            article_id="art-123",
            title="Nhạc demo",
            file_name="Nhac_demo.m4a",
            file_path="uploads/audio/Nhac_demo.m4a",
            file_size_bytes=1048576,
            duration_seconds=60.0,
            duration_formatted="01:00",
            codec="aac",
            bit_rate_kbps=320,
            lossless=True,
            message="Trích xuất âm thanh chất lượng gốc thành công!",
        )
        self.assertTrue(res.success)
        self.assertTrue(res.lossless)
        self.assertEqual(res.duration_formatted, "01:00")


if __name__ == "__main__":
    unittest.main()
