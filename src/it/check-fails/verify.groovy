assert !new File(basedir, '.claude/skills/demo').exists() : 'check must not install anything'
def log = new File(basedir, 'build.log').text
assert log.contains('not installed; sync will install it')
assert log.contains('1 skill(s) differ from skill-sommelier.lock.json')
