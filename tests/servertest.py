#!/usr/bin/env python3
"""Headless server test harness for better-than-village checkouts.

Usage: tests/servertest.py <project-dir> <commands-file> [--port N] [--keep-world]

Commands may use {expr} with variables LX/LZ captured from the last response containing "[x, ~, z]" (e.g. /locate),
for example: forceload add {LX-32} {LZ-32} {LX+32} {LZ+32}

Starts `gradlew runServer` in <project-dir>/run, waits for "Done",
executes RCON commands from <commands-file> (one per line; "#sleep N" waits N seconds;
"#wait-log REGEX [timeout]" waits for a log line), prints responses, then stops the server.
The full server log is written to <project-dir>/run/test-server.log.

Environment: BTV_JAVA_HOME (a JDK 21, else JAVA_HOME is used as is) and BTV_PROXY (host:port of an HTTP proxy
for Gradle, optional).
"""
import os, re, socket, struct, subprocess, sys, threading, time

def rcon_packet(req_id, ptype, body):
    data = struct.pack('<ii', req_id, ptype) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data

def rcon_read(sock):
    raw = b''
    while len(raw) < 4:
        chunk = sock.recv(4 - len(raw))
        if not chunk:
            raise ConnectionError('rcon closed')
        raw += chunk
    (length,) = struct.unpack('<i', raw)
    data = b''
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk:
            raise ConnectionError('rcon closed')
        data += chunk
    req_id, ptype = struct.unpack('<ii', data[:8])
    return req_id, data[8:-2].decode('utf-8', 'replace')

def main():
    args = sys.argv[1:]
    port = 25575
    keep = False
    if '--port' in args:
        i = args.index('--port'); port = int(args[i + 1]); del args[i:i + 2]
    if '--keep-world' in args:
        keep = True; args.remove('--keep-world')
    project, cmdfile = args[0], args[1]
    run = os.path.join(project, 'run')
    os.makedirs(run, exist_ok=True)
    if not keep:
        subprocess.run(['rm', '-rf', os.path.join(run, 'world')])
    with open(os.path.join(run, 'eula.txt'), 'w') as f:
        f.write('eula=true\n')
    game_port = port - 100
    with open(os.path.join(run, 'server.properties'), 'w') as f:
        f.write(f"""enable-rcon=true
rcon.password=btv
rcon.port={port}
server-port={game_port}
online-mode=false
level-seed=btv-test
spawn-protection=0
view-distance=6
simulation-distance=6
motd=btv-test
""")
    log_path = os.path.join(run, 'test-server.log')
    log = open(log_path, 'w')
    env = dict(os.environ)
    if os.environ.get('BTV_JAVA_HOME'):
        env['JAVA_HOME'] = os.environ['BTV_JAVA_HOME']
    proxy = []
    if os.environ.get('BTV_PROXY'):
        host, _, pport = os.environ['BTV_PROXY'].rpartition(':')
        proxy = [f'-D{p}.{k}={v}' for p in ('http', 'https') for k, v in (('proxyHost', host), ('proxyPort', pport))]
    proc = subprocess.Popen(['./gradlew', '--console=plain'] + proxy + ['runServer'], cwd=project, env=env,
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = []
    def pump():
        for line in proc.stdout:
            log.write(line); log.flush()
            lines.append(line)
    threading.Thread(target=pump, daemon=True).start()

    cursor = [0]

    def wait_for(regex, timeout):
        # Only lines after the previous match count, so repeated waits for the same message work.
        pat = re.compile(regex)
        start = time.time(); seen = cursor[0]
        while time.time() - start < timeout:
            while seen < len(lines):
                if pat.search(lines[seen]):
                    cursor[0] = seen + 1
                    return lines[seen]
                seen += 1
            if proc.poll() is not None:
                return None
            time.sleep(0.5)
        return None

    ok = wait_for(r'Done \(.*\)! For help', 900)
    if not ok:
        print('SERVER DID NOT START; tail of log:')
        print(''.join(lines[-80:]))
        proc.kill()
        sys.exit(2)
    print('SERVER STARTED')
    sock = socket.create_connection(('127.0.0.1', port), timeout=30)
    sock.sendall(rcon_packet(1, 3, 'btv'))
    rcon_read(sock)
    vars_ = {}
    for raw in open(cmdfile):
        cmd = raw.strip()
        if not cmd or cmd.startswith('//'):
            continue
        if cmd.startswith('#sleep'):
            time.sleep(float(cmd.split()[1])); continue
        if cmd.startswith('#wait-log'):
            parts = cmd.split(maxsplit=2)
            rest = parts[1] if len(parts) > 1 else ''
            timeout = 120
            m = re.match(r'(.*)\s+(\d+)$', cmd[len('#wait-log'):].strip())
            regex = m.group(1) if m else cmd[len('#wait-log'):].strip()
            if m: timeout = int(m.group(2))
            print(f'WAIT-LOG {regex!r}:', wait_for(regex, timeout)); continue
        cmd = re.sub(r'\{([^{}]*\b(?:LX|LZ)\b[^{}]*)\}', lambda m: str(int(eval(m.group(1), {}, dict(vars_)))), cmd)
        # Long replies arrive as several packets back to back: read until a short silence.
        sock.sendall(rcon_packet(2, 2, cmd))
        sock.settimeout(180)
        parts = [rcon_read(sock)[1]]
        sock.settimeout(0.5)
        try:
            while True:
                parts.append(rcon_read(sock)[1])
        except (socket.timeout, TimeoutError):
            pass
        sock.settimeout(180)
        resp = ''.join(parts)
        print(f'> {cmd}\n{resp}')
        loc = re.search(r'\[(-?\d+), [~\d-]+, (-?\d+)\]', resp)
        if loc:
            vars_['LX'], vars_['LZ'] = int(loc.group(1)), int(loc.group(2))
    try:
        sock.sendall(rcon_packet(3, 2, 'stop'))
    except Exception:
        pass
    try:
        proc.wait(timeout=120)
    except subprocess.TimeoutExpired:
        proc.kill()
    errors = [l for l in lines if re.search(r'(ERROR|Exception|Mixin.*(fail|error))', l)]
    print(f'--- {len(errors)} error-like log lines (first 60) ---')
    print(''.join(errors[:60]))

if __name__ == '__main__':
    main()
