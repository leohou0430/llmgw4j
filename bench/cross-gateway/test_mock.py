"""Consumer contracts for the standalone OpenAI mock used in gateway comparisons."""
import json
import socket
import subprocess
import tempfile
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class MockContracts(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            cls.port = sock.getsockname()[1]
        cls.log = tempfile.TemporaryFile(mode='w+')
        cls.process = subprocess.Popen(['java', str(ROOT / 'bench/mock/StandaloneMockUpstream.java'),
            str(cls.port), '0', '0', 'test-upstream-key'], stdout=cls.log, stderr=subprocess.STDOUT)
        for _ in range(160):
            if cls.process.poll() is not None:
                cls.log.seek(0)
                raise RuntimeError(cls.log.read())
            try:
                cls.call('/v1/models', key='test-upstream-key')
                return
            except OSError:
                time.sleep(0.1)
        raise RuntimeError('mock startup timeout')

    @classmethod
    def tearDownClass(cls):
        cls.process.terminate()
        cls.process.wait(timeout=10)
        cls.log.close()

    @classmethod
    def call(cls, path='/v1/chat/completions', body=None, key='test-upstream-key'):
        headers = {'Content-Type': 'application/json'}
        if key:
            headers['Authorization'] = 'Bearer ' + key
        req = urllib.request.Request(f'http://127.0.0.1:{cls.port}' + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                return r.status, r.headers.get_content_type(), r.read().decode()
        except urllib.error.HTTPError as e:
            return e.code, e.headers.get_content_type(), e.read().decode()

    def test_missing_provider_credentials_are_rejected(self):
        self.assertEqual(401, self.call(body={'model':'qwen-plus', 'stream':False}, key=None)[0])

    def test_word_true_in_prompt_does_not_turn_json_into_sse(self):
        status, content_type, body = self.call(body={'model':'qwen-plus',
            'messages':[{'role':'user','content':'the word true belongs to the prompt'}], 'stream':False})
        self.assertEqual(200, status)
        self.assertEqual('application/json', content_type)
        result = json.loads(body)
        self.assertEqual('chat.completion', result['object'])
        self.assertEqual('qwen-plus', result['model'])

    def test_stream_usage_frame_follows_openai_shape(self):
        status, content_type, body = self.call(body={'model':'qwen-plus', 'stream':True})
        self.assertEqual(200, status)
        frames = [line[6:] for line in body.splitlines() if line.startswith('data: ')]
        self.assertEqual('[DONE]', frames[-1])
        usage = [json.loads(frame) for frame in frames[:-1] if 'usage' in json.loads(frame)]
        self.assertEqual([], usage[0]['choices'])
        self.assertEqual(9, usage[0]['usage']['total_tokens'])


if __name__ == '__main__':
    unittest.main()
