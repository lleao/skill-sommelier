def log = new File(basedir, 'build.log').text
assert log.contains("source 'gone' unavailable")
assert log.contains('BUILD SUCCESS')
