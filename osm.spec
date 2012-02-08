%define __jar_repack %{nil}
%define debug_package %{nil}
%define __strip /bin/true
%define __os_install_post   /bin/true
%define __check_files /bin/true
Summary: osm
Name: osm
Version: 0.1.0
Release: 2
Epoch: 0
BuildArchitectures: noarch
Group: Applications
BuildRoot: %{_tmppath}/%{name}-%{version}-buildroot
License: BSD
Provides: osm
Requires: iplant-service-config
Source0: %{name}-%{version}.tar.gz

%description
iPlant OSM

%pre
getent group iplant > /dev/null || groupadd -r iplant
getent passwd iplant > /dev/null || useradd -r -g iplant -d /home/iplant -s /bin/bash -c "User for the iPlant services." iplant
exit 0

%prep
%setup -q
mkdir -p $RPM_BUILD_ROOT/etc/init.d/

%build
unset JAVA_OPTS
lein deps
lein uberjar

%install
install -d $RPM_BUILD_ROOT/usr/local/lib/osm/
install -d $RPM_BUILD_ROOT/var/run/osm/
install -d $RPM_BUILD_ROOT/var/lock/subsys/osm/
install -d $RPM_BUILD_ROOT/var/log/osm/
install -d $RPM_BUILD_ROOT/etc/osm/

install osm $RPM_BUILD_ROOT/etc/init.d/
install osm-1.0.0-SNAPSHOT-standalone.jar $RPM_BUILD_ROOT/usr/local/lib/osm/
install conf/log4j.properties $RPM_BUILD_ROOT/etc/osm/
install conf/osm.properties $RPM_BUILD_ROOT/etc/osm/

%post
/sbin/chkconfig --add osm

%preun
if [ $1 -eq 0 ] ; then
	/sbin/service osm stop >/dev/null 2>&1
	/sbin/chkconfig --del osm
fi

%postun
if [ "$1" -ge "1" ] ; then
	/sbin/service osm condrestart >/dev/null 2>&1 || :
fi

%clean
lein clean
rm -r lib/*

%files
%attr(-,iplant,iplant) /usr/local/lib/osm/
%attr(-,iplant,iplant) /var/run/osm/
%attr(-,iplant,iplant) /var/lock/subsys/osm/
%attr(-,iplant,iplant) /var/log/osm/
%attr(-,iplant,iplant) /etc/osm/

%config %attr(0644,iplant,iplant) /etc/osm/log4j.properties
%config %attr(0644,iplant,iplant) /etc/osm/osm.properties

%attr(0755,root,root) /etc/init.d/osm
%attr(0644,iplant,iplant) /usr/local/lib/osm/osm-1.0.0-SNAPSHOT-standalone.jar


