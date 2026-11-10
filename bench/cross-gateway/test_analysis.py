import unittest
from bench_analysis import parse_summary, summarize

class BenchmarkAnalysisContracts(unittest.TestCase):
    def test_k6_summary_is_read_without_fabricating_missing_metrics(self):
        data = {'metrics': {'gateway_requests': {'count':100,'rate':10},
            'gateway_failure_rate': {'value':0},
            'gateway_latency_ms': {'p(50)':5,'p(95)':8,'p(99)':9}}}
        self.assertEqual({'requests':100,'rps':10,'error_rate':0,'p50_ms':5,'p95_ms':8,'p99_ms':9},parse_summary(data))
        del data['metrics']['gateway_failure_rate']
        with self.assertRaises(ValueError): parse_summary(data)

    def test_one_failed_or_missing_round_excludes_the_whole_arm(self):
        rows = []
        for arm, errors in [('healthy',[0,0,0]),('unreliable',[0,0.1,0]),('incomplete',[0,0])]:
            for i,error in enumerate(errors):
                rows.append(dict(arm=arm,profile='wire',mode='json',round=i+1,rps=100+i,
                    p50_ms=1,p95_ms=2,p99_ms=3,error_rate=error,exit_code=0))
        result=summarize(rows,3)
        self.assertEqual(['healthy'],[r['arm'] for r in result['rankings']])
        self.assertEqual({'unreliable','incomplete'},{r['arm'] for r in result['excluded']})
        self.assertEqual(101,result['rankings'][0]['rps_median'])

    def test_wire_and_inference_are_never_combined(self):
        rows=[dict(arm='a',profile=p,mode='json',round=1,rps=r,p50_ms=1,p95_ms=2,p99_ms=3,error_rate=0,exit_code=0)
              for p,r in [('wire',1000),('inference',100)]]
        result=summarize(rows,1)
        self.assertEqual([100,1000], sorted(r['rps_median'] for r in result['rankings']))

class BalancedBenchmarkOrder(unittest.TestCase):
    def test_each_arm_occupies_each_position_in_a_complete_cycle(self):
        from compare import round_order
        arms=['web-after','vt-after','vt-before','web-before']
        rows=[round_order(arms,n) for n in range(1,5)]
        for row in rows:self.assertEqual(set(arms),set(row))
        for position in range(4):self.assertEqual(set(arms),{row[position] for row in rows})
    def test_two_arm_comparison_alternates_first_position(self):
        from compare import round_order
        self.assertEqual(['a','b','a','b'],[round_order(['a','b'],n)[0] for n in range(1,5)])

if __name__=='__main__':unittest.main()
