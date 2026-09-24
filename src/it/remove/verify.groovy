assert !new File(basedir, '.claude/skills/demo').exists() : 'removed skill must not be restored by sync'
assert !new File(basedir, 'skill-sommelier.lock.json').text.contains('"demo"')
