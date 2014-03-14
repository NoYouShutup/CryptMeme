Version 0.1.1 from http://www.metanotion.net/software/sandbox/block.html

License: See any source file.

This is a partial list of changes for I2P. For details, see history.txt
and the source control logs.

- BSkipList has an option to not keep everything in memory.
  When this option is enabled, we use the new IBSkipSpan instead of
  BSkipSpan. IBSkipSpan has the following changes:
   * Only the first key in the span, and no values, are stored in memory
   * put() and remove() read the span keys and values in from disk first
   * flush() nulls out the keys and values after flushing to disk
   * get() does a linear search through the keys on disk

- The metaIndex is stored in-memory. All "user" skiplists are not
  stored in-memory.

- Default span size changed from 127 to 16

- Use I2P random source

- Return the previous SkipList if still open from a call to getIndex()

- Add a closeIndex() method

- Commented out some System.out.println()

- Convert Errors without message or cause to RuntimeExceptions with a message and cause

- Add unique on-disk headers to each page type for robustness;
  change blockfile magic number as these headers are required

- Automatically repair some types of corruption;
  report some other corruption types without failing

- Support read-only databases

- Lots and lots of bug fixes


TODO:

- More catching and repair of corruption

- Change PAGESIZE from default 1024 to 4096? No, wastes too much disk.

