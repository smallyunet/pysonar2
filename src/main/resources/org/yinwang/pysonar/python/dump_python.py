import ast
import builtins
import tokenize

from json import JSONEncoder
from ast import *


class AstEncoder(JSONEncoder):
    def default(self, obj):
        if hasattr(obj, '__dict__'):
            dic = obj.__dict__
            dic['pysonar_node_type'] = obj.__class__.__name__
            return dic
        else:
            return str(obj)


lines = ''


def parse_dump(filename, output, end_mark):
    try:
        encoder = AstEncoder()
        tree = parse_file(filename)
        encoded = encoder.encode(tree)
        f = open(output, "w")
        f.write(encoded)
        f.close()
    finally:
        # write marker file to signal write end
        f = open(end_mark, "w")
        f.close()


def parse_file(filename):
    global lines
    with tokenize.open(filename) as f:
        lines = f.read()
    return parse_string(lines, filename)


def parse_string(string, filename=None):
    tree = ast.parse(string)
    improve_ast(tree, string)
    if filename:
        tree.filename = filename
    return tree


# short function for experiments
def p(filename):
    parse_dump(filename, "json1", "end1")


#-------------------------------------------------------------
#                   improvements to the AST
#-------------------------------------------------------------
def improve_ast(node, s):
    build_index_map(s)
    improve_node(node, s)


line_starts = []


# build global table 'idxmap' for lineno <-> index conversion
def build_index_map(s):
    global line_starts
    idx = 0
    line_starts = [0]
    while idx < len(s):
        if s[idx] == '\n':
            line_starts.append(idx + 1)
        idx += 1


# convert (line, col) to offset index
def map_idx(line, col):
    return line_starts[line - 1] + col


# convert offset index into (line, col)
def map_line_col(idx):
    line = 0
    for start in line_starts:
        if idx < start:
            break
        line += 1
    col = idx - line_starts[line - 1]
    return line, col


def improve_node(node, s):
    if isinstance(node, list):
        for n in node:
            improve_node(n, s)

    elif isinstance(node, AST):
        find_start(node, s)
        find_end(node, s)
        if hasattr(node, 'start'):
            node.lineno, node.col_offset = map_line_col(node.start)
        add_missing_names(node, s)

        for f in node_fields(node):
            improve_node(f, s)


def find_start(node, s):
    ret = None    # default value

    if hasattr(node, 'start'):
        ret = node.start

    elif isinstance(node, list):
        if node != []:
            ret = find_start(node[0], s)

    elif isinstance(node, Module):
        if node.body != []:
            ret = find_start(node.body[0], s)

    elif isinstance(node, BinOp):
        leftstart = find_start(node.left, s)
        if leftstart is not None:
            ret = leftstart
        else:
            ret = map_idx(node.lineno, node.col_offset)

    elif hasattr(node, 'lineno'):
        if node.col_offset >= 0:
            ret = map_idx(node.lineno, node.col_offset)
        else:                           # special case for """ strings
            i = map_idx(node.lineno, node.col_offset)
            while i > 0 and i + 2 < len(s) and s[i:i + 3] != '"""' and s[i:i + 3] != "'''":
                i -= 1
            ret = i
    else:
        return None

    if ret is None and hasattr(node, 'lineno'):
        raise TypeError("got None for node that has lineno", node)

    if isinstance(node, AST) and ret is not None:
        node.start = ret

    return ret


def find_end(node, s):
    the_end = None

    if hasattr(node, 'end'):
        return node.end

    elif isinstance(node, list):
        if node != []:
            the_end = find_end(node[-1], s)

    elif isinstance(node, Module):
        if node.body != []:
            the_end = find_end(node.body[-1], s)

    elif isinstance(node, Expr):
        the_end = find_end(node.value, s)

    elif isinstance(node, Constant) and isinstance(node.value, str):
        i = find_start(node, s)
        while s[i] != '"' and s[i] != "'":
            i += 1

        if i + 2 < len(s) and s[i:i + 3] == '"""':
            q = '"""'
            i += 3
        elif i + 2 < len(s) and s[i:i + 3] == "'''":
            q = "'''"
            i += 3
        elif s[i] == '"':
            q = '"'
            i += 1
        else:
            q = "'"
            i += 1

        the_end = end_seq(s, q, i)

    elif isinstance(node, Constant) and isinstance(node.value, bytes):
        the_end = find_start(node, s) + len(repr(node.value))

    elif isinstance(node, Name):
        the_end = find_start(node, s) + len(node.id)

    elif isinstance(node, Attribute):
        the_end = end_seq(s, node.attr, find_end(node.value, s))

    elif isinstance(node, (FunctionDef, AsyncFunctionDef)):
        the_end = find_end(node.body, s)

    elif isinstance(node, Lambda):
        the_end = find_end(node.body, s)

    elif isinstance(node, ClassDef):
        the_end = find_end(node.body, s)

    elif isinstance(node, Call):
        start = find_end(node.func, s)
        if start is not None:
            the_end = match_paren(s, '(', ')', start)

    elif isinstance(node, Yield):
        the_end = find_end(node.value, s)

    elif isinstance(node, Return):
        if node.value is not None:
            the_end = find_end(node.value, s)
        else:
            the_end = find_start(node, s) + len('return')

    elif (isinstance(node, For) or
          isinstance(node, While) or
          isinstance(node, If) or
          isinstance(node, IfExp)):
        if node.orelse != []:
            the_end = find_end(node.orelse, s)
        else:
            the_end = find_end(node.body, s)

    elif isinstance(node, Assign) or isinstance(node, AugAssign):
        the_end = find_end(node.value, s)

    elif isinstance(node, BinOp):
        the_end = find_end(node.right, s)

    elif isinstance(node, BoolOp):
        the_end = find_end(node.values[-1], s)

    elif isinstance(node, Compare):
        the_end = find_end(node.comparators[-1], s)

    elif isinstance(node, UnaryOp):
        the_end = find_end(node.operand, s)

    elif (isinstance(node, Constant) and
          isinstance(node.value, (int, float, complex)) and
          not isinstance(node.value, bool)):
        the_end = find_start(node, s) + len(str(node.value))

    elif isinstance(node, List):
        the_end = match_paren(s, '[', ']', find_start(node, s))

    elif isinstance(node, Subscript):
        the_end = match_paren(s, '[', ']', find_start(node, s))

    elif isinstance(node, Tuple):
        if node.elts != []:
            the_end = find_end(node.elts[-1], s)

    elif isinstance(node, Dict):
        the_end = match_paren(s, '{', '}', find_start(node, s))

    elif isinstance(node, Try):
        if node.orelse != []:
            the_end = find_end(node.orelse, s)
        elif node.handlers != []:
            the_end = find_end(node.handlers, s)
        else:
            the_end = find_end(node.body, s)

    elif isinstance(node, ExceptHandler):
        the_end = find_end(node.body, s)

    elif isinstance(node, Pass):
        the_end = find_start(node, s) + len('pass')

    elif isinstance(node, Break):
        the_end = find_start(node, s) + len('break')

    elif isinstance(node, Continue):
        the_end = find_start(node, s) + len('continue')

    elif isinstance(node, Global):
        the_end = start_seq(s, '\n', find_start(node, s))

    elif isinstance(node, Import):
        the_end = find_start(node, s) + len('import')

    elif isinstance(node, ImportFrom):
        the_end = find_start(node, s) + len('from')

    else:   # can't determine node end, set to 3 chars after start
        start = find_start(node, s)
        if start is not None:
            the_end = start + 3

    if isinstance(node, AST) and the_end is not None:
        node.end = the_end

    return the_end


def add_missing_names(node, s):
    if hasattr(node, 'extra_attr'):
        return

    if isinstance(node, list):
        for n in node:
            add_missing_names(n, s)

    elif isinstance(node, ClassDef):
        head = find_start(node, s)
        start = s.find("class", head) + len("class")
        if start is not None:
            node.name_node = str_to_name(s, start)
            node._fields += ('name_node',)

    elif isinstance(node, (FunctionDef, AsyncFunctionDef)):
        # skip to "def" because it may contain decorators like @property
        head = find_start(node, s)
        start = s.find("def", head) + len("def")
        if start is not None:
            node.name_node = str_to_name(s, start)
            node._fields += ('name_node',)

        # keyword_start = find_start(node, s)
        # node.keyword_node = str_to_name(s, keyword_start)
        # node._fields += ('keyword_node',)

        if node.args.vararg is not None:
            if len(node.args.args) > 0:
                vstart = find_end(node.args.args[-1], s)
            else:
                vstart = find_end(node.name_node, s)
            if vstart is not None:
                vname = str_to_name(s, vstart)
                node.vararg_name = vname
        else:
            node.vararg_name = None
        node._fields += ('vararg_name',)

        if node.args.kwarg is not None:
            if len(node.args.args) > 0:
                kstart = find_end(node.args.args[-1], s)
            else:
                kstart = find_end(node.vararg_name, s)
            if kstart:
                kname = str_to_name(s, kstart)
                node.kwarg_name = kname
        else:
            node.kwarg_name = None
        node._fields += ('kwarg_name',)

    elif isinstance(node, Attribute):
        start = find_end(node.value, s)
        if start is not None:
            name = str_to_name(s, start)
            node.attr_name = name
            node._fields = ('value', 'attr_name')  # remove attr for node size accuracy

    elif isinstance(node, Compare):
        start = find_start(node, s)
        if start is not None:
            node.opsName = convert_ops(node.ops, s, start)
            node._fields += ('opsName',)

    elif (isinstance(node, BoolOp) or
          isinstance(node, BinOp) or
          isinstance(node, UnaryOp) or
          isinstance(node, AugAssign)):
        if hasattr(node, 'left'):
            start = find_end(node.left, s)
        else:
            start = find_start(node, s)
        if start is not None:
            ops = convert_ops([node.op], s, start)
        else:
            ops = []
        if ops != []:
            node.op_node = ops[0]
            node._fields += ('op_node',)

    elif isinstance(node, Constant) and isinstance(node.value, bytes):
        node.constant_kind = 'bytes'
        node._fields += ('constant_kind',)

    elif isinstance(node, Constant) and node.value is builtins.Ellipsis:
        node.constant_kind = 'ellipsis'
        node._fields += ('constant_kind',)

    elif (isinstance(node, Constant) and
          isinstance(node.value, (int, float, complex)) and
          not isinstance(node.value, bool)):
        value = node.value
        if isinstance(value, int):
            num_type = 'int'
            node.value = str(value)
        elif isinstance(value, float):
            num_type = 'float'
            node.value = str(value)
        elif isinstance(value, complex):
            num_type = 'complex'
            node.real = value.real
            node.imag = value.imag
            node._fields += ('real', 'imag')
        else:
            num_type = 'unsupported'

        node.num_type = num_type
        node._fields += ('num_type',)

    node.extra_attr = True


#-------------------------------------------------------------
#              utilities used by improve AST functions
#-------------------------------------------------------------

# find a sequence in a string s, returning the start point
def start_seq(s, pat, start):
    try:
        return s.index(pat, start)
    except ValueError:
        return len(s)


# find a sequence in a string s, returning the end point
def end_seq(s, pat, start):
    try:
        return s.index(pat, start) + len(pat)
    except ValueError:
        return len(s)


# find matching close paren from start
def match_paren(s, open, close, start):
    while start < len(s) and s[start] != open:
        start += 1
    if start >= len(s):
        return len(s)

    left = 1
    i = start + 1
    while left > 0 and i < len(s):
        if s[i] == open:
            left += 1
        elif s[i] == close:
            left -= 1
        i += 1
    return i


# convert string to Name
def str_to_name(s, start):
    i = start
    while i < len(s) and not is_alpha(s[i]):
        i += 1
    name_start = i

    ret = []
    while i < len(s) and is_alpha(s[i]):
        ret.append(s[i])
        i += 1
    name_end = i

    id1 = ''.join(ret)
    if id1 == '':
        return None
    else:
        name = Name(id1, None)
        name.start = name_start
        name.end = name_end
        return name


def convert_ops(ops, s, start):
    syms = []
    for op in ops:
        if type(op) in ops_map:
            syms.append(ops_map[type(op)])
        else:
            print("[WARNING] operator %s is missing from ops_map, "
                  "please report the bug on GitHub" % op)

    i = start
    j = 0
    ret = []
    while i < len(s) and j < len(syms):
        oplen = len(syms[j])
        if s[i:i + oplen] == syms[j]:
            op_node = Name(syms[j], None)
            op_node.start = i
            op_node.end = i + oplen
            ret.append(op_node)
            j += 1
            i = op_node.end
        else:
            i += 1
    return ret


# lookup table for operators for convert_ops
ops_map = {
    # compare:
    Eq: '==',
    NotEq: '!=',
    LtE: '<=',
    Lt: '<',
    GtE: '>=',
    Gt: '>',
    NotIn: 'not in',
    In: 'in',
    IsNot: 'is not',
    Is: 'is',

    # BoolOp
    Or: 'or',
    And: 'and',
    Not: 'not',
    Invert: '~',

    # bit operators
    BitOr: '|',
    BitAnd: '&',
    BitXor: '^',
    RShift: '>>',
    LShift: '<<',


    # BinOp
    Add: '+',
    Sub: '-',
    Mult: '*',
    Div: '/',
    FloorDiv: '//',
    Mod: '%',
    Pow: '**',

    # UnaryOp
    USub: '-',
    UAdd: '+',
}

ops_map[MatMult] = '@'


# get list of fields from a node
def node_fields(node):
    ret = []
    for field in node._fields:
        if field != 'ctx' and hasattr(node, field):
            ret.append(getattr(node, field))
    return ret


# get full source text where the node is from
def node_source(node):
    if hasattr(node, 'node_source'):
        return node.node_source
    else:
        return None


# utility for getting exact source code part of the node
def src(node):
    return node.node_source[node.start: node.end]


def start(node):
    if hasattr(node, 'start'):
        return node.start
    else:
        return 0


def end(node):
    if hasattr(node, 'end'):
        return node.end
    else:
        return None


def is_alpha(c):
    return (c == '_' or
            ('0' <= c <= '9') or
            ('a' <= c <= 'z') or
            ('A' <= c <= 'Z'))


# p('/path/to/a/python/module.py')
