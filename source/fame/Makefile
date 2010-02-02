################################################################################
#
#	Makefile for fame
#
################################################################################

# The directory in which the source files can be found
$SOURCEDIR=.

# The directory in which to place temporary compiled files
$BUILDDIR=../../build/fame

# The directory in which to place compiled executables and JAR files
$BINDIR=../../bin

# The Fortran 90 compiler to use and flags to use when compiling Fortran 90 
# code
F90=g95
F90FLAGS=

LDFLAGS=-lpthread -lblas -llapack

OBJ=Species.o Isomer.o Reaction.o DensityOfStates.o Network.o Input.o StrongCollision.o ReservoirState.o RateModel.o Output.o fame.o 

OBJ=$(patsubst %.o,$(BUILDDIR)/%.o,$(OBJ))

################################################################################

$(BINDIR)/fame.exe: dirs $(OBJ)  
	$(F90) $(LDFLAGS) $(OBJ) -o $(BINDIR)/fame.exe

dirs:
	mkdir -p $(BUILDDIR)
	mkdir -p $(BINDIR)

$(BUILDDIR)/%.o: %.f90
	$(F90) $(F90FLAGS) -c $< -o $@

clean:
	rm -rf $(BUILDDIR)
	rm -rf $(BINDIR)