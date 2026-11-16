"""Verify noisy load stops the runner before artifact reads or Docker operations."""
import contextlib,importlib.util,io,sys,unittest.mock as mock
from pathlib import Path
root=Path(__file__).resolve().parents[4]
spec=importlib.util.spec_from_file_location('capacity_run',root/'bench/capacity/run.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
with mock.patch.object(module.os,'cpu_count',return_value=2):
 with mock.patch.object(module.os,'getloadavg',return_value=(4,3,2)):
  assert not module.host_load(1.5)['acceptable']
  assert module.host_load(0)['acceptable']
  with mock.patch.object(module.subprocess,'check_output',side_effect=AssertionError('Docker must not run')),mock.patch.object(sys,'argv',['run.py','--before','/nonexistent','--after','/nonexistent','--output','/nonexistent']),contextlib.redirect_stderr(io.StringIO()):
   try:module.main()
   except SystemExit as error:assert error.code==2
   else:raise AssertionError('noisy trial accepted')
 with mock.patch.object(module.os,'getloadavg',return_value=(3,3,2)):
  assert module.host_load(1.5)['acceptable']
print('PASS: threshold, explicit diagnostic override, and fail-before-Docker preflight')
