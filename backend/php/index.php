<?php
/**
 * 房东小助手 · 云端同步 API（PHP + PDO，MySQL / SQLite 通用）
 *
 * 与 Cloudflare Workers、腾讯 EdgeOne、阿里云 ESA 三套后端**接口完全一致**，
 * App 和电脑版填同一个域名格式即可使用。
 *
 * 部署：把本目录整个上传到网站根目录，让 /api/* 都指向 index.php
 * （Apache 用同级 .htaccess，Nginx 见 README）。
 */

require __DIR__ . '/config.php';

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: ' . CORS_ORIGIN);
header('Access-Control-Allow-Headers: Content-Type, Authorization');
header('Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS');
header('Cache-Control: no-store');

$method = isset($_SERVER['REQUEST_METHOD']) ? strtoupper($_SERVER['REQUEST_METHOD']) : 'GET';
if ($method === 'OPTIONS') {
    echo json_encode(['code' => 0]);
    exit;
}

/* ============================ 工具 ============================ */

function ok($data)
{
    echo json_encode(['code' => 0, 'msg' => 'ok', 'data' => $data ?: new stdClass()], JSON_UNESCAPED_UNICODE);
    exit;
}

function fail($code, $msg, $status = 200)
{
    http_response_code($status);
    echo json_encode(['code' => $code, 'msg' => $msg], JSON_UNESCAPED_UNICODE);
    exit;
}

function now_ms()
{
    return (int) round(microtime(true) * 1000);
}

function body_json()
{
    $raw = file_get_contents('php://input');
    if ($raw !== false && strlen($raw) > 2 * 1024 * 1024) {
        fail(400, '请求体过大');
    }
    $v = json_decode($raw ?: '{}', true);
    return is_array($v) ? $v : [];
}

function bearer_token()
{
    $h = '';
    if (isset($_SERVER['HTTP_AUTHORIZATION'])) {
        $h = $_SERVER['HTTP_AUTHORIZATION'];
    } elseif (isset($_SERVER['REDIRECT_HTTP_AUTHORIZATION'])) {
        // Apache + CGI 时 authorization 头会被改名
        $h = $_SERVER['REDIRECT_HTTP_AUTHORIZATION'];
    } elseif (function_exists('apache_request_headers')) {
        $all = apache_request_headers();
        foreach ($all as $k => $v) {
            if (strcasecmp($k, 'Authorization') === 0) {
                $h = $v;
                break;
            }
        }
    }
    if (preg_match('/^Bearer\s+(.+)$/i', trim($h), $m)) {
        return trim($m[1]);
    }
    return '';
}

/** 转小写（没有 mbstring 扩展时退回 strtolower，账号一般是英文/数字，够用） */
function lower($v)
{
    return function_exists('mb_strtolower') ? mb_strtolower($v, 'UTF-8') : strtolower($v);
}

function str_len($v)
{
    return function_exists('mb_strlen') ? mb_strlen($v, 'UTF-8') : strlen($v);
}

function new_uid()
{
    return bin2hex(random_bytes(12));
}

/** 与 JS 版完全相同的哈希格式：pbkdf2$2048$saltB64$dkB64 */
function hash_password($password)
{
    $salt = random_bytes(16);
    $dk = hash_pbkdf2('sha256', $password, $salt, 2048, 32, true);
    return 'pbkdf2$2048$' . base64_encode($salt) . '$' . base64_encode($dk);
}

function verify_password($password, $stored)
{
    $parts = explode('$', (string) $stored);
    if (count($parts) !== 4 || $parts[0] !== 'pbkdf2') {
        return false;
    }
    $rounds = (int) $parts[1];
    $salt = base64_decode($parts[2], true);
    $dk = base64_decode($parts[3], true);
    if ($salt === false || $dk === false) {
        return false;
    }
    $calc = hash_pbkdf2('sha256', $password, $salt, $rounds, strlen($dk), true);
    return hash_equals($dk, $calc);
}

function r2($v)
{
    $n = is_numeric($v) ? (float) $v : 0.0;
    if (!is_finite($n)) {
        $n = 0.0;
    }
    return round($n, 2);
}

function s($v, $max = 120)
{
    if ($v === null || $v === false) {
        return '';
    }
    if (is_array($v) || is_object($v)) {
        return '';
    }
    $str = (string) $v;
    if (function_exists('mb_substr')) {
        return mb_substr($str, 0, $max, 'UTF-8');
    }
    return substr($str, 0, $max);
}

function norm_meter($m)
{
    if (!is_array($m)) {
        $m = [];
    }
    return [
        'label' => s(isset($m['label']) ? $m['label'] : '', 40),
        'start' => r2(isset($m['start']) ? $m['start'] : 0),
        'end'   => r2(isset($m['end']) ? $m['end'] : 0),
        'usage' => r2(isset($m['usage']) ? $m['usage'] : 0),
        'fee'   => r2(isset($m['fee']) ? $m['fee'] : 0),
    ];
}

function norm_meters($arr, $max = 8)
{
    $out = [];
    if (is_array($arr)) {
        $i = 0;
        foreach ($arr as $m) {
            if ($i >= $max) {
                break;
            }
            $out[] = norm_meter($m);
            $i++;
        }
    }
    return $out;
}

/** 与 JS normalizeBill 字段、截断长度一一对应 */
function normalize_bill($raw)
{
    if (!is_array($raw)) {
        $raw = [];
    }
    $uid = (isset($raw['uid']) && is_string($raw['uid']) && strlen($raw['uid']) <= 64 && $raw['uid'] !== '')
        ? $raw['uid'] : new_uid();

    $extras = [];
    if (isset($raw['extras']) && is_array($raw['extras'])) {
        $i = 0;
        foreach ($raw['extras'] as $e) {
            if ($i >= 40) {
                break;
            }
            $i++;
            if (!is_array($e)) {
                continue;
            }
            $extras[] = [
                'name'       => s(isset($e['name']) ? $e['name'] : '', 40),
                'usageBased' => !empty($e['usageBased']),
                'usage'      => r2(isset($e['usage']) ? $e['usage'] : 0),
                'amount'     => r2(isset($e['amount']) ? $e['amount'] : 0),
            ];
        }
    }

    $updated = isset($raw['updatedAt']) ? (float) $raw['updatedAt'] : (float) now_ms();
    if (!is_finite($updated) || $updated <= 0) {
        $updated = now_ms();
    }

    return [
        'uid'        => $uid,
        'room'       => s(isset($raw['room']) ? $raw['room'] : '', 60),
        'tenant'     => s(isset($raw['tenant']) ? $raw['tenant'] : '', 60),
        'month'      => s(isset($raw['month']) ? $raw['month'] : '', 20),
        'note'       => s(isset($raw['note']) ? $raw['note'] : '', 200),
        'waterMeters' => norm_meters(isset($raw['waterMeters']) ? $raw['waterMeters'] : []),
        'gasMeters'   => norm_meters(isset($raw['gasMeters']) ? $raw['gasMeters'] : []),
        'meters'      => norm_meters(isset($raw['meters']) ? $raw['meters'] : []),
        'waterFee'   => r2(isset($raw['waterFee']) ? $raw['waterFee'] : 0),
        'gasFee'     => r2(isset($raw['gasFee']) ? $raw['gasFee'] : 0),
        'elecFee'    => r2(isset($raw['elecFee']) ? $raw['elecFee'] : 0),
        'extras'     => $extras,
        'updatedAt'  => (int) $updated,
    ];
}

/* ============================ 数据库 ============================ */

function db()
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $isSqlite = strpos(DB_DSN, 'sqlite:') === 0;
    if ($isSqlite) {
        $dir = dirname(__DIR__) . '/data';
        if (!is_dir($dir)) {
            @mkdir($dir, 0775, true);
        }
    }

    $pdo = new PDO(DB_DSN, DB_USER, DB_PASS, [
        PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
    ]);

    if ($isSqlite) {
        $pdo->exec('CREATE TABLE IF NOT EXISTS users (
            id TEXT PRIMARY KEY,
            username TEXT NOT NULL,
            username_lower TEXT NOT NULL UNIQUE,
            password TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )');
        $pdo->exec('CREATE TABLE IF NOT EXISTS sessions (
            token TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            expires_at INTEGER NOT NULL
        )');
        $pdo->exec('CREATE TABLE IF NOT EXISTS bills (
            user_id TEXT NOT NULL,
            uid TEXT NOT NULL,
            payload TEXT NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY (user_id, uid)
        )');
    }

    return $pdo;
}

/* ============================ 存储操作 ============================ */

function create_user($username, $lower, $hash)
{
    $pdo = db();
    $stmt = $pdo->prepare('SELECT id FROM users WHERE username_lower = ?');
    $stmt->execute([$lower]);
    if ($stmt->fetch()) {
        return null;
    }
    $user = [
        'id'         => bin2hex(random_bytes(8)),
        'username'   => $username,
        'created_at' => now_ms(),
    ];
    $ins = $pdo->prepare('INSERT INTO users (id, username, username_lower, password, created_at) VALUES (?, ?, ?, ?, ?)');
    $ins->execute([$user['id'], $username, $lower, $hash, $user['created_at']]);
    return $user;
}

function get_user($lower)
{
    $stmt = db()->prepare('SELECT id, username, password, created_at FROM users WHERE username_lower = ?');
    $stmt->execute([$lower]);
    $row = $stmt->fetch();
    return $row ?: null;
}

function save_session($token, $userId, $expiresAt)
{
    db()->prepare('INSERT INTO sessions (token, user_id, expires_at) VALUES (?, ?, ?)')
        ->execute([$token, $userId, $expiresAt]);
}

function get_session($token)
{
    $stmt = db()->prepare('SELECT user_id, expires_at FROM sessions WHERE token = ?');
    $stmt->execute([$token]);
    $row = $stmt->fetch();
    if (!$row) {
        return null;
    }
    if ((int) $row['expires_at'] < now_ms()) {
        db()->prepare('DELETE FROM sessions WHERE token = ?')->execute([$token]);
        return null;
    }
    return ['user_id' => $row['user_id']];
}

function delete_session($token)
{
    db()->prepare('DELETE FROM sessions WHERE token = ?')->execute([$token]);
}

/** 只有更新的数据才覆盖（old.updatedAt <= new.updatedAt） */
function put_bill($userId, $bill)
{
    $pdo = db();
    $stmt = $pdo->prepare('SELECT payload FROM bills WHERE user_id = ? AND uid = ?');
    $stmt->execute([$userId, $bill['uid']]);
    $row = $stmt->fetch();
    if ($row) {
        $old = json_decode($row['payload'], true);
        $oldTs = is_array($old) && isset($old['updatedAt']) ? (int) $old['updatedAt'] : 0;
        if ($bill['updatedAt'] < $oldTs) {
            return false; // 旧数据不覆盖新数据
        }
        $upd = $pdo->prepare('UPDATE bills SET payload = ?, updated_at = ? WHERE user_id = ? AND uid = ?');
        $upd->execute([json_encode($bill, JSON_UNESCAPED_UNICODE), $bill['updatedAt'], $userId, $bill['uid']]);
        return true;
    }
    $ins = $pdo->prepare('INSERT INTO bills (user_id, uid, payload, updated_at) VALUES (?, ?, ?, ?)');
    $ins->execute([$userId, $bill['uid'], json_encode($bill, JSON_UNESCAPED_UNICODE), $bill['updatedAt']]);
    return true;
}

function get_bills($userId)
{
    $stmt = db()->prepare('SELECT payload FROM bills WHERE user_id = ? ORDER BY updated_at DESC');
    $stmt->execute([$userId]);
    $out = [];
    foreach ($stmt->fetchAll() as $row) {
        $b = json_decode($row['payload'], true);
        if (is_array($b)) {
            $out[] = $b;
        }
    }
    return $out;
}

function delete_bill($userId, $uid)
{
    db()->prepare('DELETE FROM bills WHERE user_id = ? AND uid = ?')->execute([$userId, $uid]);
}

/* ============================ 路由 ============================ */

$path = '/';
if (isset($_SERVER['PATH_INFO'])) {
    $path = $_SERVER['PATH_INFO'];
} else {
    $uri = isset($_SERVER['REQUEST_URI']) ? $_SERVER['REQUEST_URI'] : '/';
    $path = parse_url($uri, PHP_URL_PATH);
    $script = isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '';
    if ($script && strpos($path, $script) === 0) {
        $path = substr($path, strlen($script));
    } elseif ($script) {
        $dir = rtrim(dirname($script), '/');
        if ($dir !== '' && strpos($path, $dir . '/') === 0) {
            $path = substr($path, strlen($dir));
        }
    }
}
$path = '/' . trim($path, '/');
// 允许直接访问 index.php/api/xxx 和 /api/xxx
if (strpos($path, '/index.php') === 0) {
    $path = substr($path, strlen('/index.php'));
    $path = '/' . trim($path, '/');
}

$parts = explode('/', trim($path, '/'));
$last = end($parts);
$prev = count($parts) >= 2 ? $parts[count($parts) - 2] : '';

try {
    /* 健康检查 */
    if ($last === 'health' || $last === '' || $last === 'index.php') {
        ok(['platform' => 'php', 'time' => now_ms(), 'version' => '2.10']);
    }

    /* 注册 */
    if ($last === 'register' && $method === 'POST') {
        $body = body_json();
        $username = trim(s(isset($body['username']) ? $body['username'] : '', 32));
        $password = s(isset($body['password']) ? $body['password'] : '', 128);
        if (str_len($username) < 2) {
            fail(400, '账号至少 2 个字符');
        }
        if (strlen($password) < 6) {
            fail(400, '密码至少 6 位');
        }
        $user = create_user($username, lower($username), hash_password($password));
        if (!$user) {
            fail(409, '该账号已被注册', 409);
        }
        $token = bin2hex(random_bytes(16));
        save_session($token, $user['id'], now_ms() + TOKEN_TTL_MS);
        ok(['user_id' => $user['id'], 'username' => $user['username'], 'token' => $token]);
    }

    /* 登录 */
    if ($last === 'login' && $method === 'POST') {
        $body = body_json();
        $username = trim(s(isset($body['username']) ? $body['username'] : '', 32));
        $password = s(isset($body['password']) ? $body['password'] : '', 128);
        if ($username === '' || $password === '') {
            fail(400, '请输入账号和密码');
        }
        $user = get_user(lower($username));
        if (!$user || !verify_password($password, $user['password'])) {
            fail(401, '账号或密码错误');
        }
        $token = bin2hex(random_bytes(16));
        save_session($token, $user['id'], now_ms() + TOKEN_TTL_MS);
        ok(['user_id' => $user['id'], 'username' => $user['username'], 'token' => $token]);
    }

    /* 以下需要登录 */
    $token = bearer_token();
    if ($token === '') {
        fail(401, '未登录或登录已过期');
    }
    $session = get_session($token);
    if (!$session) {
        fail(401, '未登录或登录已过期');
    }
    $userId = $session['user_id'];

    if ($last === 'logout' && $method === 'POST') {
        delete_session($token);
        ok([]);
    }

    if ($last === 'bills' && $method === 'GET') {
        $bills = get_bills($userId);
        ok(['bills' => $bills, 'count' => count($bills)]);
    }

    if ($last === 'sync' && $method === 'POST') {
        $body = body_json();
        $incoming = [];
        if (isset($body['bills']) && is_array($body['bills'])) {
            $incoming = array_slice($body['bills'], 0, MAX_BILLS_PER_REQUEST);
        } elseif (isset($body['bill']) && is_array($body['bill'])) {
            $incoming = [$body['bill']];
        }
        $accepted = 0;
        foreach ($incoming as $raw) {
            $bill = normalize_bill($raw);
            if ($bill['room'] === '' && $bill['month'] === '') {
                continue;
            }
            put_bill($userId, $bill);
            $accepted++;
        }
        $all = get_bills($userId);
        ok(['uploaded' => $accepted, 'bills' => $all, 'count' => count($all)]);
    }

    if ($last === 'delete' && ($method === 'POST' || $method === 'DELETE')) {
        $body = body_json();
        $uid = s(isset($body['uid']) ? $body['uid'] : '', 64);
        if ($uid === '') {
            fail(400, '缺少账单 uid');
        }
        delete_bill($userId, $uid);
        ok(['uid' => $uid]);
    }

    fail(404, '接口不存在：' . $path, 404);
} catch (Throwable $e) {
    fail(500, '服务端错误：' . $e->getMessage());
}
