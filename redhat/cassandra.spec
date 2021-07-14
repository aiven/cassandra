%define __jar_repack %{nil}
# Turn off the brp-python-bytecompile script
%global __os_install_post %(echo '%{__os_install_post}' | sed -e 's!/usr/lib[^[:space:]]*/brp-python-bytecompile[[:space:]].*$!!g')

# rpmbuild should not barf when it spots we ship
# binary executable files in our 'noarch' package
%define _binaries_in_noarch_packages_terminate_build   0

%global username cassandra

%define relname apache-cassandra-%{version}
%define cassandraX cassandra3

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
mkdir -p %{buildroot}/usr/share/%{cassandraX}/bin
mkdir -p %{buildroot}/usr/share/%{cassandraX}/sbin
mkdir -p %{buildroot}/usr/share/%{cassandraX}/lib
mkdir -p %{buildroot}/%{_sysconfdir}/%{cassandraX}/default.conf
mkdir -p %{buildroot}/%{_sysconfdir}/rc.d/init.d
mkdir -p %{buildroot}/%{_sysconfdir}/security/limits.d
mkdir -p %{buildroot}/%{_sysconfdir}/default
mkdir -p %{buildroot}/usr/sbin
mkdir -p %{buildroot}/usr/bin
mkdir -p %{buildroot}/var/lib/%{cassandraX}/commitlog
mkdir -p %{buildroot}/var/lib/%{cassandraX}/data
mkdir -p %{buildroot}/var/lib/%{cassandraX}/saved_caches
mkdir -p %{buildroot}/var/lib/%{cassandraX}/hints
mkdir -p %{buildroot}/var/run/%{cassandraX}
mkdir -p %{buildroot}/var/log/%{username}
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
cp -p redhat/%{username} %{buildroot}/%{_sysconfdir}/rc.d/init.d/%{cassandraX}
cp -p redhat/%{username}.conf %{buildroot}/%{_sysconfdir}/security/limits.d/{cassandraX}.conf
cp -p redhat/default %{buildroot}/%{_sysconfdir}/default/%{cassandraX}

# copy cassandra bundled libs
cp -pr lib/* %{buildroot}/usr/share/%{cassandraX}/lib/

# copy stress jar
cp -p build/tools/lib/stress.jar %{buildroot}/usr/share/%{cassandraX}/

# copy binaries
mv bin/cassandra %{buildroot}/usr/share/%{cassandraX}/sbin
cp -p bin/* %{buildroot}/usr/share/%{cassandraX}/bin
cp -p tools/bin/* %{buildroot}/usr/share/%{cassandraX}/bin

# copy cassandra, thrift jars
cp build/apache-cassandra-%{version}.jar %{buildroot}/usr/share/%{cassandraX}/
cp build/apache-cassandra-thrift-%{version}.jar %{buildroot}/usr/share/%{cassandraX}/

%clean
%{__rm} -rf %{buildroot}

%pre
getent group %{username} >/dev/null || groupadd -r %{username}
getent passwd %{username} >/dev/null || \
useradd -d /var/lib/%{username} -g %{username} -M -r %{username}
exit 0

%files
%defattr(0644,root,root,0755)
%doc CHANGES.txt LICENSE.txt README.asc NEWS.txt NOTICE.txt CASSANDRA-14092.txt
%attr(755,root,root) /usr/share/%{cassandraX}/bin/cassandra-stress
%attr(755,root,root) /usr/share/%{cassandraX}/bin/cqlsh
%attr(755,root,root) /usr/share/%{cassandraX}/bin/cqlsh.py
%attr(755,root,root) /usr/share/%{cassandraX}/bin/debug-cql
%attr(755,root,root) /usr/share/%{cassandraX}/bin/nodetool
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstableloader
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstablescrub
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstableupgrade
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstableutil
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstableverify
%attr(755,root,root) /usr/share/%{cassandraX}/bin/stop-server
%attr(755,root,root) /usr/share/%{cassandraX}/sbin/cassandra
%attr(755,root,root) /%{_sysconfdir}/rc.d/init.d/%{cassandraX}
%{_sysconfdir}/default/%{cassandraX}
%{_sysconfdir}/security/limits.d/%{cassandraX}.conf
/usr/share/%{cassandraX}*
%config(noreplace) /%{_sysconfdir}/%{cassandraX}
%attr(755,%{username},%{username}) %config(noreplace) /var/lib/%{cassandraX}/*
%attr(755,%{username},%{username}) /var/log/%{cassandraX}*
%attr(755,%{username},%{username}) /var/run/%{cassandraX}*
/usr/lib/python2.7/site-packages/cqlshlib/
/usr/lib/python2.7/site-packages/cassandra_pylib*.egg-info

%post
alternatives --install /%{_sbindir}/cassandra cassandra /usr/share/%{cassandraX}/sbin/cassandra 0 \
    --slave /%{_sysconfdir}/%{username} %{username}_conf /%{_sysconfdir}/%{cassandraX} \
    --slave /usr/share/%{username} %{username}_share /usr/share/%{cassandraX} \
    --slave /var/lib/%{username} %{username}_lib /var/lib/%{cassandraX} \
    --slave /var/run/%{username} %{username}_run /var/run/%{cassandraX} \
    --slave /%{_sysconfdir}/rc.d/init.d/%{username} %{username}_init /%{_sysconfdir}/rc.d/init.d/%{cassandraX} \
    --slave /%{_sysconfdir}/security/limits.d/%{username}.conf %{username}_limits /%{_sysconfdir}/security/limits.d/{cassandraX}.conf \
    --slave /%{_sysconfdir}/default/%{username} %{username}_default_conf /%{_sysconfdir}/default/%{username} \
    --slave /usr/bin/cassandra-stress cassandra-stress /usr/share/%{cassandraX}/bin/cassandra-stress \
    --slave /usr/bin/cqlsh cqlsh /usr/share/%{cassandraX}/bin/cqlsh \
    --slave /usr/bin/cqlsh.py cqlsh.py /usr/share/%{cassandraX}/bin/cqlsh.py \
    --slave /usr/bin/debug-cql debug-cql /usr/share/%{cassandraX}/bin/debug-cql \
    --slave /usr/bin/nodetool nodetool /usr/share/%{cassandraX}/bin/nodetool \
    --slave /usr/bin/sstableloader sstableloader /usr/share/%{cassandraX}/bin/sstableloader \
    --slave /usr/bin/sstablescrub sstablescrub /usr/share/%{cassandraX}/bin/sstablescrub \
    --slave /usr/bin/sstableupgrade sstableupgrade /usr/share/%{cassandraX}/bin/sstableupgrade \
    --slave /usr/bin/sstableutil sstableutil /usr/share/%{cassandraX}/bin/sstableutil \
    --slave /usr/bin/sstableverify sstableverify /usr/share/%{cassandraX}/bin/sstableverify \
    --slave /usr/bin/stop-server stop-server /usr/share/%{cassandraX}/bin/stop-server
alternatives --install /%{_sysconfdir}/%{username}/conf %{username}_conf /%{_sysconfdir}/%{username}/default.conf/ 0
exit 0

%preun
# only delete alternative on removal, not upgrade
if [ "$1" = "0" ]; then
    alternatives --remove %{username} /%{_sysconfdir}/%{username}/default.conf/
fi
exit 0


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
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstabledump
%attr(755,root,root) /usr/share/%{cassandraX}/bin/cassandra-stressd
%attr(755,root,root) /usr/share/%{cassandraX}/bin/compaction-stress
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstableexpiredblockers
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstablelevelreset
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstablemetadata
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstableofflinerelevel
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstablerepairedset
%attr(755,root,root) /usr/share/%{cassandraX}/bin/sstablesplit


%changelog
* Mon Dec 05 2016 Michael Shuler <mshuler@apache.org>
- 2.1.17, 2.2.9, 3.0.11, 3.10
- Reintroduce RPM packaging
