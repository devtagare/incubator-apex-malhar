<?php
header("Content-type: application/json");
$redis = new Redis();
//$redis->connect('127.0.0.1');
$redis->connect('node5.morado.com');
$redis->select(11);

$value = $redis->get(1);
print $value;
?>
