import json
import tempfile
import unittest
from pathlib import Path
from scripts import style_boundary as boundary


class StyleBoundaryFixturesTest(unittest.TestCase):
    def test_forbidden_primitives_and_wildcard_import_are_detected(self):
        for source,rule in (
            ('import androidx.compose.material3.*','material3-import'),
            ('import androidx.compose.material3.Button as Action','material3-import'),
            ('import androidx.compose.material3.TextButton','material3-import'),
            ('import androidx.compose.ui.window.Dialog','raw-dialog'),
            ('import com.polinalinen.madre.ui.theme.AppColors','legacy-theme'),
            ('val c = Color(0xFF_FF_00_00)','color-hex'),
            ('val f = FontFamily.Serif','system-font'),
            ('val f = Typeface.DEFAULT','system-font'),
            ('Toast.makeText(context, text, 0)','toast'),
            ('AlertDialog(onDismissRequest = {})','raw-dialog'),
            ('androidx.compose.material3.Switch(checked=true)','material3-qualified-control'),
            ('MaterialTheme(colorScheme = palette) {}','material-theme-constructor'),
        ):
            with self.subTest(source=source):
                self.assertIn(rule,{v['rule'] for v in boundary.scan_text('fixture.kt',source)})

    def test_comments_literals_and_nested_comments_are_not_code(self):
        source='// Color(0xFFFFFFFF)\n/* /* nested */ Dialog() */\nval example="FontFamily.Serif"\nval raw="""Toast.makeText(x)"""\n'
        self.assertEqual([],boundary.scan_text('fixture.kt',source))
        self.assertEqual(['raw-dialog'],[v['rule'] for v in boundary.scan_text('fixture.kt',source+'\nDialog()')])

    def test_text_and_icon_imports_do_not_hide_controls_or_wildcard(self):
        self.assertEqual([],boundary.scan_text('fixture.kt','import androidx.compose.material3.Text\nimport androidx.compose.material3.Icon'))
        self.assertEqual(1,len(boundary.scan_text('fixture.kt','import androidx.compose.material3.TextButton')))
        self.assertEqual(1,len(boundary.scan_text('fixture.kt','import androidx.compose.material3.*')))

    def test_exact_baseline_cannot_allow_a_new_violation_or_sibling_file(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);main=root/boundary.MAIN;main.mkdir(parents=True)
            source=main/'Legacy.kt';source.write_text('val c=Color(0xFF000000)\n')
            records=boundary.scan(root)
            baseline=root/boundary.BASELINE;baseline.parent.mkdir(parents=True)
            baseline.write_text(json.dumps({'tracking':'https://github.com/kamenevd/polinalinen-madre/pull/61','reason':'fixture','count':len(records),'violations':records}))
            self.assertEqual(0,boundary.check(root)['new'])
            source.write_text(source.read_text()+'val f=FontFamily.Serif\n')
            with self.assertRaisesRegex(AssertionError,'NEW style'):boundary.check(root)
            source.write_text('val c=Color(0xFF000000)\n')
            (main/'Sibling.kt').write_text(source.read_text())
            with self.assertRaisesRegex(AssertionError,'NEW style'):boundary.check(root)
            (main/'Sibling.kt').unlink();source.write_text('')
            self.assertTrue(boundary.check(root)['zero_legacy'])

    def test_scan_does_not_exempt_any_legacy_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            for folder in ('ui/theme','ui/razvorot','navigation','shelf','ui/screens'):
                p=root/boundary.MAIN/'com/polinalinen/madre'/folder/'Example.kt'
                p.parent.mkdir(parents=True,exist_ok=True);p.write_text('Dialog()')
            self.assertEqual(5,len(boundary.scan(root)))


    def test_only_exact_kit_owner_path_and_package_are_lawful(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            kit = root / boundary.KIT
            kit.mkdir(parents=True)
            source = "package com.polinalinen.madre.ui.kit\nimport androidx.compose.material3.Button\nColor(0xFF000000)"
            (kit / 'Paper.kt').write_text(source)
            self.assertEqual([], boundary.scan(root))
            for folder, package in (
                (kit.parent / 'kit-legacy', 'com.polinalinen.madre.ui.kit'),
                (kit / 'legacy', 'com.polinalinen.madre.ui.kit'),
                (kit, 'com.polinalinen.madre.ui.screens'),
            ):
                p = folder / 'Example.kt'
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text(source.replace('package com.polinalinen.madre.ui.kit', 'package ' + package))
                self.assertEqual(2, len(boundary.scan(root)))
                p.unlink()

    def test_foundation_fields_and_custom_font_assignment_also_require_kit(self):
        for source, rule in (
            ('BasicTextField(value=x)', 'bare-field'),
            ('Text(text, fontFamily = MyFamily)', 'font-assignment'),
            ('Icon(Icons.Default.Settings)', 'filled-icon'),
        ):
            self.assertIn(rule, {v['rule'] for v in boundary.scan_text('fixture.kt', source)})

if __name__=='__main__':unittest.main()
