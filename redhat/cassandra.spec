%define __jar_repack %{nil}
# Turn off the brp-python-bytecompile script
%global __os_install_post %(echo '%{__os_install_post}' | sed -e 's!/usr/lib[^[:space:]]*/brp-python-bytecompile[[:space:]].*$!!g')

# rpmbuild should not barf when it spots we ship
# binary executable files in our 'noarch' package
%define _binaries_in_noarch_packages_terminate_build   0

%global username cassandra
%global uid_offset 100

%define relname apache-cassandra-%{version}
%define cassandra_major_version 3
%define cassandraX cassandra%{cassandra_major_version}

Name:          %{cassandraX}
Epoch:         %{epoch}
Version:       %{version}
Release:       %{revision}
Summary:       Cassandra is a highly scalable, eventually consistent, distributed, structured key-value store.

Group:         Development/Libraries
License:       Apache Software License 2.0
URL:           http://cassandra.apache.org/
Source0:       %{relname}-src.tar.gz
BuildRoot:     %{_tmppath}/%{relname}root-%(%{__id_u} -n)

BuildRequires: ant >= 1.9
BuildRequires: ant-junit >= 1.9

Requires:      jre >= 1.8.0
Requires:      python(abi) >= 2.7
Requires(pre): user(cassandra)
Requires(pre): group(cassandra)
Requires(pre): shadow-utils
Conflicts:     cassandra
Provides:      user(cassandra)
Provides:      group(cassandra)

BuildArch:     noarch

# Don't examine the .so files we bundle for dependencies
AutoReqProv:   no

%description
Cassandra is a distributed (peer-to-peer) system for the management and storage of structured data.

%prep
%setup -q -n %{relname}-src

%build
export LANG=en_US.UTF-8
export JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
ant clean jar -Dversion=%{version}

%install
%{__rm} -rf %{buildroot}
mkdir -p %{buildroot}/%{_sysconfdir}/%{cassandraX}
mkdir -p %{buildroot}/usr/share/%{cassandraX}
mkdir -p %{buildroot}/usr/share/%{cassandraX}/lib
mkdir -p %{buildroot}/%{_sysconfdir}/%{cassandraX}/default.conf
mkdir -p %{buildroot}/usr/%{cassandraX}/sbin
mkdir -p %{buildroot}/usr/%{cassandraX}/bin
mkdir -p %{buildroot}/var/lib/%{cassandraX}/commitlog
mkdir -p %{buildroot}/var/lib/%{cassandraX}/data
mkdir -p %{buildroot}/var/lib/%{cassandraX}/saved_caches
mkdir -p %{buildroot}/var/lib/%{cassandraX}/hints
mkdir -p %{buildroot}/var/run/%{cassandraX}
mkdir -p %{buildroot}/var/log/%{cassandraX}
( cd pylib && python2.7 setup.py install --no-compile --root %{buildroot}; )

# patches for data and log paths
patch -p1 < debian/patches/001cassandra_yaml_dirs.dpatch
patch -p1 < debian/patches/002cassandra_logdir_fix.dpatch
# uncomment hints_directory path
sed -i 's/^# hints_directory:/hints_directory:/' conf/cassandra.yaml

# remove batch, powershell, and other files not being installed
rm -f conf/*.ps1
rm -f bin/*.bat
rm -f bin/*.orig
rm -f bin/*.ps1
rm -f bin/cassandra.in.sh
rm -f lib/sigar-bin/*winnt*  # strip segfaults on dll..
rm -f tools/bin/*.bat
rm -f tools/bin/cassandra.in.sh

# copy default configs
cp -pr conf/* %{buildroot}/%{_sysconfdir}/%{cassandraX}/default.conf/

# step on default config with our redhat one
cp -p redhat/%{username}.in.sh %{buildroot}/usr/share/%{cassandraX}/%{username}.in.sh

# copy cassandra bundled libs
cp -pr lib/* %{buildroot}/usr/share/%{cassandraX}/lib/

# copy stress jar
cp -p build/tools/lib/stress.jar %{buildroot}/usr/share/%{cassandraX}/

# copy binaries
mv bin/cassandra %{buildroot}/usr/%{cassandraX}/sbin/
cp -p bin/* %{buildroot}/usr/%{cassandraX}/bin/
cp -p tools/bin/* %{buildroot}/usr/%{cassandraX}/bin/

# copy cassandra, thrift jars
cp build/apache-cassandra-%{version}.jar %{buildroot}/usr/share/%{cassandraX}/
cp build/apache-cassandra-thrift-%{version}.jar %{buildroot}/usr/share/%{cassandraX}/

%clean
%{__rm} -rf %{buildroot}

%pre
# offset username gid/uid a little to avoid clashes with some pre-created users
getent group %{username} >/dev/null || groupadd -r \
-K SYS_GID_MIN=$((`grep -v '#' /etc/login.defs | grep SYS_GID_MIN | tr -d -c [:digit:]`+%{uid_offset})) \
-K SYS_GID_MAX=$((`grep -v '#' /etc/login.defs | grep SYS_GID_MAX | tr -d -c [:digit:]`-%{uid_offset})) \
%{username}
getent passwd %{username} >/dev/null || \
useradd -d /var/lib/%{username} -g %{username} -M -r \
-K SYS_UID_MIN=$((`grep -v '#' /etc/login.defs | grep SYS_UID_MIN | tr -d -c [:digit:]`+%{uid_offset})) \
-K SYS_UID_MAX=$((`grep -v '#' /etc/login.defs | grep SYS_UID_MAX | tr -d -c [:digit:]`-%{uid_offset})) \
%{username}
exit 0

%files
%defattr(0644,root,root,0755)
%doc CHANGES.txt LICENSE.txt README.asc NEWS.txt NOTICE.txt CASSANDRA-14092.txt
%attr(755,root,root) /usr/%{cassandraX}/bin/cassandra-stress
%attr(755,root,root) /usr/%{cassandraX}/bin/cqlsh
%attr(755,root,root) /usr/%{cassandraX}/bin/cqlsh.py
%attr(755,root,root) /usr/%{cassandraX}/bin/debug-cql
%attr(755,root,root) /usr/%{cassandraX}/bin/nodetool
%attr(755,root,root) /usr/%{cassandraX}/bin/sstableloader
%attr(755,root,root) /usr/%{cassandraX}/bin/sstablescrub
%attr(755,root,root) /usr/%{cassandraX}/bin/sstableupgrade
%attr(755,root,root) /usr/%{cassandraX}/bin/sstableutil
%attr(755,root,root) /usr/%{cassandraX}/bin/sstableverify
%attr(755,root,root) /usr/%{cassandraX}/bin/stop-server
%attr(755,root,root) /usr/%{cassandraX}/sbin/cassandra
/usr/share/%{cassandraX}*
%config(noreplace) /%{_sysconfdir}/%{cassandraX}
%attr(755,%{username},%{username}) %config(noreplace) /var/lib/%{cassandraX}/*
%attr(755,%{username},%{username}) /var/log/%{cassandraX}*
%attr(755,%{username},%{username}) /var/run/%{cassandraX}*
/usr/lib/python2.7/site-packages/cqlshlib/
/usr/lib/python2.7/site-packages/cassandra_pylib*.egg-info

%package tools
Summary:       Extra tools for Cassandra. Cassandra is a highly scalable, eventually consistent, distributed, structured key-value store.
Group:         Development/Libraries
Requires:      %{cassandraX} = %{epoch}:%{version}-%{revision}
Conflicts:     cassandra-tools

%description tools
Cassandra is a distributed (peer-to-peer) system for the management and storage of structured data.
.
This package contains extra tools for working with Cassandra clusters.

%files tools
%attr(755,root,root) /usr/%{cassandraX}/bin/sstabledump
%attr(755,root,root) /usr/%{cassandraX}/bin/cassandra-stressd
%attr(755,root,root) /usr/%{cassandraX}/bin/compaction-stress
%attr(755,root,root) /usr/%{cassandraX}/bin/sstableexpiredblockers
%attr(755,root,root) /usr/%{cassandraX}/bin/sstablelevelreset
%attr(755,root,root) /usr/%{cassandraX}/bin/sstablemetadata
%attr(755,root,root) /usr/%{cassandraX}/bin/sstableofflinerelevel
%attr(755,root,root) /usr/%{cassandraX}/bin/sstablerepairedset
%attr(755,root,root) /usr/%{cassandraX}/bin/sstablesplit


%changelog
* Mon Dec 05 2016 Michael Shuler <mshuler@apache.org>
- 2.1.17, 2.2.9, 3.0.11, 3.10
- Reintroduce RPM packaging
