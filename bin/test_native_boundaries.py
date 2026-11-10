import tempfile
import unittest
from pathlib import Path
import importlib.util
spec = importlib.util.spec_from_file_location("native_boundaries", Path(__file__).with_name("check-native-boundaries.py"))
module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
check, java_code = module.check, module.java_code

class NativeBoundaryTest(unittest.TestCase):
    def write(self, root, name, content):
        path=root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
    def pom(self):
        return '<project xmlns="http://maven.apache.org/POM/4.0.0"><properties><llmgw.foundation.enforcer.skip>false</llmgw.foundation.enforcer.skip></properties></project>'
    def test_fully_qualified_framework_types_cannot_bypass_the_guard(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.write(root,'llmgw-core/pom.xml',self.pom())
            self.write(root,'llmgw-core/src/main/java/A.java','class A { org.springframework.context.ApplicationContext factory; }')
            self.assertTrue(any('framework type' in e for e in check(root)[0]))
    def test_framework_names_in_comments_and_strings_are_not_types(self):
        value='// org.springframework.context.Foo\nclass A { String message="reactor.core.Foo"; /* org.springframework.X */ }'
        self.assertNotIn('org.springframework',java_code(value));self.assertNotIn('reactor.core',java_code(value))
    def test_two_flat_implementations_require_an_aggregator(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for module in ['llmgw-storage-a','llmgw-storage-b']:
                self.write(root,module+'/pom.xml',self.pom())
            self.assertTrue(any('flat implementation' in e for e in check(root)[0]))
    def test_missing_extension_implementation_and_duplicate_names_fail(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            for module in ['llmgw-a','llmgw-b']:
                self.write(root,module+'/pom.xml',self.pom())
                self.write(root,module+'/src/main/resources/META-INF/llmgw/extensions/example.Spi','same=example.Missing\n')
            errors=check(root)[0]
            self.assertTrue(any('source missing' in e for e in errors));self.assertTrue(any('duplicate extension' in e for e in errors))
    def test_foundation_cannot_skip_the_transitive_dependency_rule(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.write(root,'llmgw-core/pom.xml','<project xmlns="http://maven.apache.org/POM/4.0.0"/>')
            self.assertTrue(any('transitive dependency enforcement' in e for e in check(root)[0]))

    def test_dubbo_cannot_reenter_through_http_or_foundation(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.write(root,'llmgw-app/pom.xml',self.pom())
            self.write(root,'llmgw-app/src/main/java/Bad.java','class Bad { org.apache.dubbo.rpc.model.FrameworkModel model; }')
            self.assertTrue(any('direct Dubbo type' in e for e in check(root)[0]))

if __name__ == '__main__':unittest.main()
