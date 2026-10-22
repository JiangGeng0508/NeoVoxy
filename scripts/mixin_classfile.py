"""Small class-file reader for validation; never loads or initializes target classes."""

import struct
from dataclasses import dataclass
from pathlib import Path
from zipfile import ZipFile


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0

    def take(self, size):
        data = self.data[self.pos:self.pos + size]
        if len(data) != size:
            raise ValueError("Truncated class file")
        self.pos += size
        return data

    def u1(self):
        return self.take(1)[0]

    def u2(self):
        return struct.unpack(">H", self.take(2))[0]

    def u4(self):
        return struct.unpack(">I", self.take(4))[0]


@dataclass
class Member:
    name: str
    descriptor: str
    access: int
    annotations: dict
    parameter_annotations: dict


class ClassFile:
    def __init__(self, data):
        r = Reader(data)
        if r.u4() != 0xCAFEBABE:
            raise ValueError("Not a class file")
        r.take(4)  # minor/major
        self.pool = [None] * r.u2()
        i = 1
        while i < len(self.pool):
            tag = r.u1()
            if tag == 1:
                # Names/descriptors/annotation selectors use ASCII. Preserve Java's NUL encoding.
                self.pool[i] = r.take(r.u2()).replace(b'\xc0\x80', b'\x00').decode('utf-8', 'surrogatepass')
            elif tag in (3, 4):
                self.pool[i] = struct.unpack('>i' if tag == 3 else '>f', r.take(4))[0]
            elif tag in (5, 6):
                r.take(8)
                i += 1
            elif tag in (7, 8, 16, 19, 20):
                self.pool[i] = (tag, r.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                self.pool[i] = (tag, r.u2(), r.u2())
            elif tag == 15:
                r.take(3)
            else:
                raise ValueError(f"Unknown constant-pool tag {tag}")
            i += 1
        self.access = r.u2()
        self.name = self.class_name(r.u2())
        self.parent = self.class_name(r.u2())
        self.interfaces = [self.class_name(r.u2()) for _ in range(r.u2())]
        self.fields = self.members(r)
        self.methods = self.members(r)
        self.annotations = self.attributes(r)
        if r.pos != len(data):
            raise ValueError("Trailing class-file data")

    def class_name(self, index):
        return self.pool[self.pool[index][1]] if index else None

    def annotation(self, r):
        name = self.pool[r.u2()]
        values = {}
        for _ in range(r.u2()):
            key = self.pool[r.u2()]
            values[key] = self.value(r)
        return name, values

    def value(self, r):
        tag = chr(r.u1())
        if tag in 'BCDFIJSZsc':
            return self.pool[r.u2()]
        if tag == 'e':
            return [self.pool[r.u2()], self.pool[r.u2()]]
        if tag == '@':
            name, values = self.annotation(r)
            return {'annotation': name, **values}
        if tag == '[':
            return [self.value(r) for _ in range(r.u2())]
        raise ValueError(f"Unknown annotation value tag {tag}")

    def attributes(self, r, parameters=None):
        annotations = {}
        for _ in range(r.u2()):
            name = self.pool[r.u2()]
            data = r.take(r.u4())
            if name in ('RuntimeVisibleAnnotations', 'RuntimeInvisibleAnnotations'):
                ar = Reader(data)
                for _ in range(ar.u2()):
                    kind, values = self.annotation(ar)
                    annotations[kind] = values
            elif parameters is not None and name in ('RuntimeVisibleParameterAnnotations', 'RuntimeInvisibleParameterAnnotations'):
                ar = Reader(data)
                for index in range(ar.u1()):
                    for _ in range(ar.u2()):
                        kind, values = self.annotation(ar)
                        parameters.setdefault(index, {})[kind] = values
        return annotations

    def members(self, r):
        members = []
        for _ in range(r.u2()):
            access, name, descriptor = r.u2(), self.pool[r.u2()], self.pool[r.u2()]
            parameters = {}
            annotations = self.attributes(r, parameters)
            members.append(Member(name, descriptor, access, annotations, parameters))
        return members


class ClassPath:
    def __init__(self, paths):
        self.entries, self.cache = [], {}
        for raw in paths:
            path = Path(raw)
            if not path.exists():
                raise ValueError(f"Missing classpath entry: {path}")
            self.entries.append(path if path.is_dir() else ZipFile(path))

    def close(self):
        for entry in self.entries:
            if isinstance(entry, ZipFile):
                entry.close()

    def resolve(self, name):
        name = name.replace('.', '/')
        if name not in self.cache:
            self.cache[name] = None
            for entry in self.entries:
                if isinstance(entry, Path):
                    path = entry / (name + '.class')
                    data = path.read_bytes() if path.is_file() else None
                else:
                    try:
                        data = entry.read(name + '.class')
                    except KeyError:
                        data = None
                if data is not None:
                    self.cache[name] = ClassFile(data)
                    break
        return self.cache[name]


def descriptor_parts(descriptor):
    """Return exact JVM argument and return descriptors, including arrays."""
    import re
    types = re.findall(r'\[*L[^;]+;|\[*[ZBCSIJFDV]', descriptor)
    if not descriptor.startswith('(') or not types:
        raise ValueError(f"Invalid method descriptor: {descriptor}")
    if '(' + ''.join(types[:-1]) + ')' + types[-1] != descriptor:
        raise ValueError(f"Invalid method descriptor: {descriptor}")
    return types[:-1], types[-1]
