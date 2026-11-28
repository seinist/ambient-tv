"""Trace the selected ELF back to the compiler invocations that contributed to it."""
from pathlib import Path
import shlex
from core_acceleration_audit import DRIVER, ASSEMBLER, assembler_source, build_commands, compile_source


def output_file(args, cwd):
    if "-o" not in args or args.index("-o") + 1 >= len(args):
        return None
    return (cwd / args[args.index("-o") + 1]).resolve()


def inputs(args, cwd):
    # The driver passes either individual object/archive paths or comma-separated
    # -Wl arguments. Response files have already been expanded by build_commands.
    pieces = []
    for arg in args:
        if arg.startswith("-Wl,"):
            pieces.extend(arg[4:].split(","))
        else:
            pieces.append(arg)
    result = set()
    for part in pieces:
        if not part.startswith("-") and Path(part).suffix in {".o", ".obj", ".a"}:
            result.add((cwd / part).resolve())
    return result


def source_dependencies(args, cwd):
    """Read the compiler-generated Make depfile for this exact linked object.
    Include only the primary rule, not -MP's empty phony rules.
    """
    source = compile_source(args)
    found = {(cwd / source).resolve()} if source else set()
    dep = None
    for i, token in enumerate(args):
        if token == "-MF" and i + 1 < len(args):
            dep = args[i + 1]
        elif token.startswith("-MF") and len(token) > 3:
            dep = token[3:]
    if dep is None:
        output = output_file(args, cwd)
        if output is not None:
            for candidate in (Path(str(output) + ".d"), output.with_suffix(".d")):
                if candidate.is_file():
                    dep = str(candidate)
                    break
    if dep is not None:
        file = cwd / dep
        if file.is_file():
            rule = file.read_text(errors="strict").replace("\\\n", " ").splitlines()[0]
            _, separator, prerequisites = rule.partition(":")
            if not separator:
                raise ValueError(f"Invalid compiler dependency rule: {file}")
            found.update((cwd / value.replace("$", "$")).resolve()
                         for value in shlex.split(prerequisites, comments=False))
    return found


def target_commands(log, cwd, root, library, required_sources=()):
    root = Path(root).resolve()
    library = Path(library).resolve()
    objects, archives, links = {}, {}, {}
    object_dirs = {}
    archive_records = {}
    for driver, args, directory in build_commands(log, cwd, root, strict=True):
        if DRIVER.fullmatch(driver):
            output = output_file(args, directory)
            if output is None:
                continue
            if compile_source(args):
                objects[output] = args
                object_dirs[output] = directory
            elif "-shared" in args:
                links[output] = (args, directory)
        elif ASSEMBLER.fullmatch(driver):
            output = output_file(args, directory)
            source = assembler_source(args)
            if output is not None and source is not None:
                objects[output] = ["-c", source, *args]
                object_dirs[output] = directory
        else:
            # GNU/LLVM ar accepts flags as the first argument, followed by the
            # output archive and objects. Include multiple incremental adds.
            archive = next((i for i, part in enumerate(args)
                            if not part.startswith("-") and Path(part).suffix == ".a"), None)
            if archive is not None:
                output = (directory / args[archive]).resolve()
                archives.setdefault(output, set()).update(inputs(args[archive + 1:], directory))
                archive_records[output] = True
    candidates = [path for path in links if path == library]
    if not candidates:
        # ndk-build links in obj/local and copies the result to libs/<abi>.
        candidates = [path for path in links if path.name == library.name]
    if len(candidates) != 1:
        raise ValueError(f"Expected one link invocation for {library.name}; got {candidates}")
    target = candidates[0]
    link, directory = links[target]
    pending = list(inputs(link, directory))
    # Resolve local -l dependencies if an archive with that name is produced in
    # this build and the linker search directories identify it.
    search_dirs = []
    names = []
    index = 0
    while index < len(link):
        token = link[index]
        index += 1
        if token in {"-L", "-l"} and index < len(link):
            token += link[index]
            index += 1
        if token.startswith("-L"):
            search_dirs.append((directory / token[2:]).resolve())
        elif token.startswith("-l") and len(token) > 2:
            names.append(token[2:])
    for name in names:
        archive_name = name[1:] if name.startswith(":") else f"lib{name}.a"
        for search in search_dirs:
            candidate = search / archive_name
            if candidate in archives or candidate.is_file():
                pending.append(candidate)
                break
    visited = set()
    unresolved = set()
    selected = []
    while pending:
        path = pending.pop()
        if path in visited:
            continue
        visited.add(path)
        if path in objects:
            selected.append(objects[path])
        elif path in archives:
            pending.extend(archives[path])
        elif path.is_relative_to(root):
            unresolved.add(str(path.relative_to(root)))
    if not selected:
        raise ValueError(f"No auditable compiled objects for {library.name}")
    required = {str(relative): (root / "src" / relative).resolve()
                for relative in required_sources}
    evidence = {relative: False for relative in required}
    if required:
        # Only dependencies of objects reachable from the selected ELF count.
        for obj in visited & objects.keys():
            deps = source_dependencies(objects[obj], object_dirs[obj])
            for relative, path in required.items():
                if path in deps:
                    evidence[relative] = True
        missing = [relative for relative, linked in evidence.items() if not linked]
        if missing:
            raise ValueError(f"Missing linked patched-source evidence: {missing}")
    return selected + [link], {
        "patched_source_evidence": evidence,
        "linked_output": str(target),
        "linked_compile_commands": len(selected),
        "linked_archives": len(visited & archives.keys()),
        "unresolved_local_inputs": sorted(unresolved),
        "unrelated_compile_commands": len(objects) - len(selected),
    }
