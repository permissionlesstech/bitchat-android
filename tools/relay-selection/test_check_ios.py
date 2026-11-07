import unittest
from check_ios import declaration, validate_cases

HEADER = 'id\tcsv\tlatitude\tlongitude\tcount\texpected\n'
ROW = 'one\tties.csv\t0\t0\t1\twss://alpha.example\n'


class VectorInputTest(unittest.TestCase):
    def test_valid_manifest(self):
        validate_cases(HEADER + ROW)

    def test_empty_manifest_rejected(self):
        for text in ('', HEADER):
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate_cases(text)

    def test_bad_header_and_duplicate_id_rejected(self):
        for text in ('wrong\n' + ROW, HEADER + ROW + ROW):
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate_cases(text)

    def test_malformed_fields_rejected(self):
        for row in (ROW.replace('ties.csv', '../ties.csv'), ROW.replace('\t0\t0\t', '\tnan\t0\t'), ROW.replace('\t1\t', '\t2147483648\t'), ROW.replace('wss://alpha.example', '')):
            with self.subTest(row=row), self.assertRaises(ValueError):
                validate_cases(HEADER + row)

    def test_extraction_preserves_nested_declaration(self):
        text = 'struct Example {\n    func value() {\n        if true {\n        }\n    }\n}\n'
        self.assertEqual('    func value() {\n        if true {\n        }\n    }\n', declaration(text, 'func value('))

    def test_missing_duplicate_or_unclosed_declaration_rejected(self):
        for text in ('', 'struct X {\n}\nstruct X {\n}\n', 'struct X {\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                declaration(text, 'struct X')


if __name__ == '__main__':
    unittest.main()
