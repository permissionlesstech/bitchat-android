"""Bounded anonymous HTTPS reads for public corpus artifacts; no local data is uploaded."""

from collections import OrderedDict
import hashlib
import io
import re
import urllib.error
import urllib.parse
import urllib.request


USER_AGENT = "conversational-corpus-fetch/1.0"


def public_request(url, **kwargs):
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError("Corpus sources must use anonymous HTTPS URLs")
    return urllib.request.Request(url, headers={"User-Agent": USER_AGENT}, **kwargs)


class BoundedHttpFile(io.RawIOBase):
    """Seekable public Parquet input that cannot silently download a whole large shard.

    PyArrow reads only the footer and chosen column chunks. Cache and network budgets
    are independent. Validate every Content-Range, including the total artifact size.
    """

    def __init__(self, url, size, *, opener=urllib.request.urlopen,
                 max_download=32 * 1024**2, max_read=16 * 1024**2, cache_bytes=12 * 1024**2):
        super().__init__()
        self.cache = OrderedDict()
        public_request(url)
        if size <= 0:
            raise ValueError("Invalid source size")
        self.url, self.size = url, size
        self.request_url = url
        self.opener = opener
        self.max_download, self.max_read, self.cache_bytes = max_download, max_read, cache_bytes
        self.position = self.downloaded = 0
        self.ranges = []

    def readable(self):
        return True

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        position = offset if whence == 0 else self.position + offset if whence == 1 else self.size + offset if whence == 2 else -1
        if not 0 <= position <= self.size:
            raise ValueError("Invalid corpus seek")
        self.position = position
        return position

    def read(self, size=-1):
        if self.closed:
            raise ValueError("Closed public corpus stream")
        size = self.size - self.position if size < 0 else min(size, self.size - self.position)
        if size == 0:
            return b""
        start, end = self.position, self.position + size
        for offset, data in list(self.cache.items()):
            if offset <= start and end <= offset + len(data):
                self.cache.move_to_end(offset)
                self.position = end
                return data[start - offset:end - offset]
        if size > self.max_read or self.downloaded + size > self.max_download:
            raise ValueError("Public corpus range budget exceeded")
        request = public_request(self.request_url)
        request.add_header("Range", f"bytes={start}-{end - 1}")
        try:
            response = self.opener(request, timeout=45)
        except urllib.error.HTTPError as error:
            if error.code != 403 or self.request_url == self.url:
                raise
            self.request_url = self.url
            request = public_request(self.url)
            request.add_header("Range", f"bytes={start}-{end - 1}")
            response = self.opener(request, timeout=45)
        with response:
            match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)", response.headers.get("Content-Range", ""))
            if response.status != 206 or not match or tuple(map(int, match.groups())) != (start, end - 1, self.size):
                raise ValueError("Source did not honor the requested byte range")
            data = response.read(size + 1)
            # Reuse only in memory. Anonymous provider redirect signatures never enter logs/manifests.
            self.request_url = getattr(response, "geturl", lambda: self.url)()
        if len(data) != size:
            raise ValueError("Truncated public corpus range")
        self.downloaded += size
        self.position = end
        self.ranges.append({"offset": start, "bytes": size, "sha256": hashlib.sha256(data).hexdigest()})
        self.cache[start] = data
        while self.cache and sum(map(len, self.cache.values())) > self.cache_bytes:
            self.cache.popitem(last=False)
        return data

    def readinto(self, buffer):
        data = self.read(len(buffer))
        buffer[:len(data)] = data
        return len(data)

    def close(self):
        self.cache.clear()
        super().close()
