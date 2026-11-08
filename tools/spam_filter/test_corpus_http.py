import io
import unittest

from tools.spam_filter.corpus_http import BoundedHttpFile


class CorpusRangeTest(unittest.TestCase):
    def reader(self, *, status=206, wrong_range=False, truncated=False, **limits):
        calls = []
        content = b"synthetic public corpus bytes only"

        def opener(request, timeout):
            requested = request.get_header("Range").removeprefix("bytes=")
            start, end = map(int, requested.split("-"))
            calls.append((start, end))
            body = content[start:end + 1]
            response = io.BytesIO(body[:-1] if truncated else body)
            response.status = status
            response.headers = {"Content-Range": "bytes 0-0/1" if wrong_range else f"bytes {start}-{end}/{len(content)}"}
            self.assertEqual("conversational-corpus-fetch/1.0", request.get_header("User-agent"))
            self.assertIsNone(request.get_header("Authorization"))
            return response

        return BoundedHttpFile("https://example.invalid/corpus.parquet", len(content), opener=opener, **limits), calls, content

    def test_seeks_and_overlapping_cached_reads_preserve_exact_bytes(self):
        reader, calls, content = self.reader()
        self.assertEqual(content[:15], reader.read(15))
        reader.seek(3)
        self.assertEqual(content[3:8], reader.read(5))
        self.assertEqual(1, len(calls))
        reader.seek(-4, 2)
        self.assertEqual(content[-4:], reader.read(4))
        self.assertEqual(b"", reader.read())

    def test_ignored_ranges_wrong_offsets_and_truncation_cannot_silently_corrupt_data(self):
        for options in [{"status": 200}, {"wrong_range": True}, {"truncated": True}]:
            reader, _, _ = self.reader(**options)
            with self.assertRaises(ValueError):
                reader.read(8)

    def test_download_and_read_limits_apply_before_network_requests(self):
        reader, calls, _ = self.reader(max_download=10, max_read=8)
        with self.assertRaises(ValueError):
            reader.read(9)
        self.assertFalse(calls)
        reader.read(8)
        with self.assertRaises(ValueError):
            reader.read(3)
        self.assertEqual(1, len(calls))

    def test_credentials_and_local_urls_are_not_accepted_as_public_corpus_sources(self):
        for url in ["file:///synthetic", "http://example.invalid/data", "https://user:secret@example.invalid/data"]:
            with self.assertRaises(ValueError):
                BoundedHttpFile(url, 10)


if __name__ == "__main__":
    unittest.main()
