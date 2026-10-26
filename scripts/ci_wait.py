#!/usr/bin/env python3
"""One model-free, total-deadline exact-SHA Actions waiter."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

REQUIRED = ['Workflow integrity', 'Android quality']
CODES = {'success': 0, 'failure': 1, 'cancelled': 2, 'timeout': 3, 'no-run': 4, 'transport': 5, 'mismatch': 6}


class Waiter:
    def __init__(self, sha, repo, output, seconds=2400, gh='/usr/bin/gh'):
        self.sha, self.repo, self.gh = sha, repo, gh
        self.output = Path(output); self.output.mkdir(parents=True, exist_ok=False)
        os.chmod(self.output, 0o700)
        self.deadline = time.monotonic() + seconds
        self.run_id = None
        self.seq = 0

    def command(self, *args):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0: raise TimeoutError
        self.seq += 1
        command = [self.gh, *args]
        if args[0] != 'api': command += ['--repo', self.repo]
        result = subprocess.run(command, capture_output=True, text=True, timeout=remaining)
        (self.output / ('%02d.log' % self.seq)).write_text(result.stdout + result.stderr)
        return result

    def finish(self, outcome, final=None, reason=None):
        data = {'sha': self.sha, 'run_id': self.run_id, 'outcome': outcome, 'exit_code': CODES[outcome], 'final': final, 'reason': reason, 'required_jobs': REQUIRED}
        (self.output / 'receipt.json').write_text(json.dumps(data, indent=2))
        print('CI %s sha=%s run=%s receipt=%s' % (outcome, self.sha, self.run_id, self.output / 'receipt.json'), flush=True)
        return CODES[outcome]

    def failed_tail(self, final):
        for job in final.get('jobs', []):
            if job.get('conclusion') == 'failure':
                result = self.command('run', 'view', str(self.run_id), '--job', str(job['databaseId']), '--log-failed')
                if result.returncode:
                    continue
                # gh prefixes each line with job<TAB>step<TAB>; emit only last failed step.
                lines = result.stdout.splitlines()
                if lines:
                    last = lines[-1].split('\t', 2)[:2]
                    selected = [line for line in lines if line.split('\t', 2)[:2] == last]
                    print('\n'.join(selected[-60:]), flush=True)

    def wait(self):
        try:
            while self.run_id is None:
                result = self.command('api', 'repos/' + self.repo + '/actions/workflows/quality-gates.yml/runs?head_sha=' + self.sha + '&per_page=100')
                if result.returncode: return self.finish('transport', reason='discovery failed')
                runs = json.loads(result.stdout)['workflow_runs']
                matching = [r for r in runs if r['head_sha'] == self.sha]
                if matching:
                    self.run_id = max(matching, key=lambda r: r['id'])['id']
                    (self.output / 'discovered.json').write_text(json.dumps(matching, indent=2))
                    break
                remaining = self.deadline - time.monotonic()
                if remaining <= 0: return self.finish('no-run')
                time.sleep(min(10, remaining))
            watched = self.command('run', 'watch', str(self.run_id), '--exit-status', '--interval', '15')
            final_result = self.command('run', 'view', str(self.run_id), '--json', 'databaseId,headSha,status,conclusion,jobs,url')
            if final_result.returncode: return self.finish('transport', reason='final readback failed')
            final = json.loads(final_result.stdout)
            (self.output / 'final.json').write_text(json.dumps(final, indent=2))
            if final.get('headSha') != self.sha or final.get('databaseId') != self.run_id:
                return self.finish('mismatch', final)
            if final.get('status') != 'completed': return self.finish('transport', final, 'watch ended before completion')
            conclusion = final.get('conclusion')
            if conclusion == 'cancelled': return self.finish('cancelled', final)
            if conclusion == 'timed_out': return self.finish('timeout', final)
            if conclusion != 'success':
                self.failed_tail(final)
                return self.finish('failure', final)
            jobs = final.get('jobs', [])
            for name in REQUIRED:
                matches = [j for j in jobs if j.get('name') == name]
                if len(matches) != 1 or matches[0].get('status') != 'completed' or matches[0].get('conclusion') != 'success':
                    return self.finish('failure', final, 'missing/non-success required job: ' + name)
            if watched.returncode: return self.finish('transport', final, 'watch transport failed')
            return self.finish('success', final)
        except (subprocess.TimeoutExpired, TimeoutError):
            return self.finish('timeout' if self.run_id else 'no-run')
        except (OSError, ValueError, KeyError, TypeError) as exc:
            return self.finish('transport', reason=type(exc).__name__)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('sha'); p.add_argument('--repo', default='kamenevd/polinalinen-madre')
    p.add_argument('--output', default=None)
    a = p.parse_args()
    if not re.fullmatch('[0-9a-f]{40}', a.sha): p.error('exact lowercase full 40-character SHA required')
    output = a.output or str(Path.home() / '.cache/madre/ci' / (a.sha + '-' + str(time.time_ns())))
    return Waiter(a.sha, a.repo, output).wait()


if __name__ == '__main__': sys.exit(main())
