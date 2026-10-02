import os
import re
import sys
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

class RangeRequestHandler(SimpleHTTPRequestHandler):
    def end_headers(self):
        self.send_header('Accept-Ranges', 'bytes')
        super().end_headers()

    def send_head(self):
        if 'Range' not in self.headers:
            return super().send_head()
        
        path = self.translate_path(self.path)
        f = None
        try:
            f = open(path, 'rb')
        except OSError:
            self.send_error(404, "File not found")
            return None
        
        fs = os.fstat(f.fileno())
        total_length = fs.st_size
        range_header = self.headers['Range']
        match = re.search(r'bytes=(\d+)-(\d*)', range_header)
        if not match:
            return super().send_head()
        
        start = int(match.group(1))
        end = int(match.group(2)) if match.group(2) else total_length - 1
        if start >= total_length or end >= total_length or start > end:
            self.send_error(416, "Requested Range Not Satisfiable")
            self.send_header('Content-Range', f'bytes */{total_length}')
            self.end_headers()
            f.close()
            return None
        
        length = end - start + 1
        self.send_response(206)
        self.send_header('Content-type', self.guess_type(path))
        self.send_header('Content-Range', f'bytes {start}-{end}/{total_length}')
        self.send_header('Content-Length', str(length))
        self.send_header('Last-Modified', self.date_time_string(fs.st_mtime))
        self.end_headers()
        f.seek(start)
        self.range_length = length
        return f

    def copyfile(self, source, outputfile):
        if not hasattr(self, 'range_length'):
            super().copyfile(source, outputfile)
            return
        
        remaining = self.range_length
        bufsize = 64 * 1024
        while remaining > 0:
            read_size = min(remaining, bufsize)
            buf = source.read(read_size)
            if not buf:
                break
            try:
                outputfile.write(buf)
            except (ConnectionResetError, BrokenPipeError):
                break
            remaining -= len(buf)

if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    server_address = ('', port)
    httpd = ThreadingHTTPServer(server_address, RangeRequestHandler)
    print(f"Serving HTTP on port {port} with Range support...")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
